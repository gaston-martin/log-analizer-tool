package com.gastonmartin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gastonmartin.model.LogMessage;
import com.gastonmartin.model.Tags;
import com.gastonmartin.util.Utils;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.lang.String.format;
import static java.util.stream.Collectors.*;


/**
 * Class for interacting with elasticsearch service
 * Requires some configuration from config.properties file to figure out which host to connect
 *
 */
public class LogProcessorService {

    /* Static parameters */

    /* Property names */
    private static final String HOSTNAME_PROPERY = "ELASTIC_HOSTNAME";
    private static final String APPNAME_PROPERTY = "APP_NAME";
    private static final String PAGE_SIZE_PROPERTY = "PAGE_SIZE";
    private static final String MAX_RESULTS_PROPERTY = "MAX_RESULTS";

    /* Page size when querying elastic */
    @Getter @Setter
    private int pageSize;

    private static final int DEFAULT_PAGE_SIZE = 500;
    private static final int DEFAULT_MAX_RESULTS = 10000;

    /* Private variables for storing config values */
    private final String HOSTNAME;
    private final String APPNAME;

    @Getter @Setter
    private int maxResults;


    /* Reference to an instance of ReplacementsService */
    private final ReplacementsService replacementsService;

    /* global ObjectMapper for conversion of JSON */
    private static ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    /**
     * Class constructor. Reads values from config.properties and initializes an instance of ReplacementsService
     */
    public LogProcessorService() {
        try (InputStream input = LogProcessorService.class.getClassLoader().getResourceAsStream("config.properties")) {
            Properties prop = new Properties();

            if (input == null) {
                throw new RuntimeException("unable to load config.properties");
            }

            //load a properties file from class path, inside static method
            prop.load(input);

            //get the property value and print it out
            HOSTNAME = prop.getProperty(HOSTNAME_PROPERY);
            APPNAME = prop.getProperty(APPNAME_PROPERTY);
            pageSize = Integer.parseInt(prop.getProperty(PAGE_SIZE_PROPERTY, String.valueOf(DEFAULT_PAGE_SIZE)));
            maxResults = Integer.parseInt(prop.getProperty(MAX_RESULTS_PROPERTY, String.valueOf(DEFAULT_MAX_RESULTS)));

            if (HOSTNAME == null) throw new RuntimeException("Missing property " + HOSTNAME_PROPERY);
            if (APPNAME == null) throw new RuntimeException("Missing property " + APPNAME_PROPERTY);

            replacementsService = new ReplacementsService();

        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException((e));
        }
    }


    /**
     * Given an index date (i.e "2020.05.30") connects to elasticsearch as per the configuration
     * obtained in the class constructor (gathered from config.properties), downloads the messages
     * up to a predefined maximum number of results, transform all messaged to obtain more generic
     * messages and <b>write results to several .txt files.</b>
     * <b>This is the main method of this class</b>
     * @param indexDate (optional) the name of the index date, i.e. "2020.05.30". If omitted will use today's index.
     */
    public void process(String indexDate, String searchTerms) {

        if (indexDate == null){
            indexDate = Utils.getTodayIndexName();
        }
        searchTerms = searchTerms.replaceAll(" ","%20");
        List<JsonNode> resultados = getLogsFromElasticAndTransform(indexDate, searchTerms);

        //String pretty = toPrettyString(resultados.get(0));
        //System.out.println(pretty);

        // Totales
        Long totalBytesBeforeReplaces = getTotalBytesBeforeReplaces(resultados);
        Integer totalLineCount = resultados.size();

        // Ranking de mensajes con mayor aparicion
        Map<String, Long> sortedRankByMessageCount = countMessagesDesc(resultados);
        writeSortedRankToFile(sortedRankByMessageCount, "ranking_por_message_count.txt", totalLineCount.longValue());
        //dumpSortedRank(sortedRankByMessageCount,25);

        Map<String, Long> weightedRank = weightMessagesBySize(resultados);
        writeSortedRankToFile(weightedRank, "ranking_por_message_bytes.txt", totalBytesBeforeReplaces);
        dumpSortedRank(weightedRank,25);

        Map<String, Long> sortedRankBySourceCount = countMessagesBySource(resultados);
        writeSortedRankToFile(sortedRankBySourceCount, "ranking_por_source_count.txt", totalLineCount.longValue());
        dumpSortedRank(sortedRankBySourceCount, 25);


        Map<String, Long> weightMessagesBySource = weightMessagesBySource(resultados);
        writeSortedRankToFile(weightMessagesBySource, "ranking_por_source_bytes.txt",totalBytesBeforeReplaces);
        dumpSortedRank(weightMessagesBySource, 25);

        Map<String, Long> sortedRankByScopeCount = countMessagesByScope(resultados);
        writeSortedRankToFile(sortedRankByScopeCount, "ranking_por_scope_count.txt", totalLineCount.longValue());
        dumpSortedRank(sortedRankByScopeCount, 25);


        Map<String, Long> weightMessagesByScope = weightMessagesByScope(resultados);
        writeSortedRankToFile(weightMessagesByScope, "ranking_por_scope_bytes.txt",totalBytesBeforeReplaces);
        dumpSortedRank(weightMessagesByScope, 25);



        printStatistics(resultados);
        //dumpTags(resultados);



        //rate de compresion
        //usar contains de los tags
        // Ranking (agrupar por source)
        // etc

    }

    /**
     * Given an index date and search term, obtain all matching logs from elasticsearch as per the
     * instance configured in the class constructor, using pagination, and transform the log entries
     * by consecutive transform methods to allow further reduction and grouping.
     * @param indexDate the date as String such as "2020.05.30"
     * @param searchTerms the search terms (usually "*")
     * @return a List of JsonNode with message transformed
     */
    List<JsonNode> getLogsFromElasticAndTransform(@NonNull String indexDate, String searchTerms) {
        List<JsonNode> results = new ArrayList<>();
        int page = 1;
        long offset = 0L;
        Long count = getResultCount(indexDate, searchTerms);
        int realPages = BigDecimal.valueOf(count / pageSize).setScale(0, RoundingMode.HALF_UP).intValue();

        System.out.println(format("Search term \"%s\" for application %s and date %s produced %,d total results in %d pages of size %d",
                searchTerms, APPNAME, indexDate, count, realPages, pageSize));

        int maxResults= this.maxResults;
        int pages = maxResults / pageSize;

        while (page <= pages) {

            final int currentPage = page; // for lambda
            List<JsonNode> intermediateResults = getPaginatedResultsAsStream(indexDate, offset, pageSize, searchTerms)
                    .map(x -> {
                        JsonNode source = x.get("_source");
                        // Measure how much bytes the original message spans and store in a new property
                        source = addSizeOfMessage(source);
                        // Apply all regular expressions from expressions.txt
                        source = applyRegularExpressions(source);
                        // Replace all these tags in place with their tag names to further association and matching
                        source = replaceTags(source, Tags.DISMISS, true);
                        // Replace all these tags in place with a combination of tag name and generic number
                        source = replaceTags(source, Tags.UNDIFERENTIATE, false);
                        // Replace the rest of numbers with generic numbers (i.e 9999999)
                        source = generifyNumbers(source, 2);
                        // Dump the message line after replacement
                        System.out.println(format("Page %d replaced: %s)", currentPage, getMessageFromNode(source)));
                        return source;
                    })
                    .collect(toList());
            results.addAll(intermediateResults);
            offset += pageSize;
            page++;
        }
        return results;
    }



    /**
     * Builds up a RestHighLevelClient for connecting to elasticsearch <b>host defined in config</b>
     * and set timeout settings for client.
     * This is a low-level method not intended for direct use.
     * @return an instance of the high level official elastic search client
     */
    private RestHighLevelClient getHighLevelClient() {
        return new RestHighLevelClient(
                RestClient.builder(
                        // Regular elasticsearchs listens on port 9201 but our infra people had hidden it behind
                        // some proxy which filters out most operations and params  ¯\_(ツ)_/¯
                        new HttpHost(HOSTNAME, 80, "http")
                )
                        .setRequestConfigCallback(
                                requestConfigBuilder -> requestConfigBuilder
                                        .setConnectTimeout(5000)
                                        .setSocketTimeout(99999)));
    }


    /**
     * Connects to elasticsearch through low-level client and perform some search on given index.
     * The name of the app (APPNAME) is taken from properties read inside the constructor of the class.
     * This is a low-level method not intended for direct use.
     * @param indexDate the date of the index as String i.e. 2020.05.30
     * @param searchParameter a search term (usually "*")
     * @return an org.elasticsearch.Response which holds a representation of the JSON returned by elastic
     * @throws IOException
     */
    private Response performSearch(@NonNull String indexDate, String searchParameter) throws IOException {

        Response response = null;
        // Esto es un try-with-resources.
        try (RestHighLevelClient client = getHighLevelClient()){

            RestClient lowLevelClient = client.getLowLevelClient();

            final String endpoint = format("/elasticsearch/%s-%s/_search?%s",
                    APPNAME,
                    indexDate,
                    searchParameter);

            response = lowLevelClient.performRequest(new Request("GET", endpoint));

            // This is important to avoid leaking connections
            client.getLowLevelClient().close();
        } catch (IOException e) {
            // Rethrow as runtime non checked exception.
            throw new RuntimeException(e);
        }

        return response;
    }


    /**
     * Perform a search against the given date of the configured app in elastic and return the count
     * This is a low-level method not intended for direct use. Is mainly used for proper pagination.
     * @param indexDate the date of the index as String i.e. 2020.05.30
     * @param searchTerms a search term (usually "*")
     * @return a Long holding the count of total results
     */
    private Long getResultCount(@NonNull String indexDate, String searchTerms) {

        JsonNode jsonNode = getPaginatedResults(indexDate, 0L, 1, searchTerms);
        Long total = jsonNode.path("total").asLong();
        return total;
    }


    /**
     * Given an index date, a search term and pagination parameters perform a paginated search and
     * return just the "hits" node of the original result object.
     * This is a low-level method not intended for direct use.
     * @param indexDate the date of the index as String i.e. 2020.05.30
     * @param from the initial offset por pagination, in number of entries
     * @param size the size of the page in number of entries
     * @param searchTerms a search term (usually "*")
     * @return a Json object containing the results from elasticsearch
     */
    private JsonNode getPaginatedResults(@NonNull String indexDate, @NonNull Long from, @NonNull Integer size, String searchTerms) {

        try {
            String fullSearchPredicate = format("from=%d&size=%d&q=%s", from, size, searchTerms);
            Response response = performSearch(indexDate, fullSearchPredicate);
            String rawBody = EntityUtils.toString(response.getEntity());
            JsonNode jsonNode = mapper.readTree(rawBody);
            return jsonNode.path("hits");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * This is a wrapper method for {@link #getPaginatedResults(String, Long, Integer, String)} that
     * produces a Stream by invoking spliterator on JsonNode.
     * <b>Please notice that it returns the "hits.hits" node of the original results node.</b>
     * This is an internal method not intended for direct use.
     * @param indexDate the date of the index as String i.e. 2020.05.30
     * @param from the initial offset por pagination, in number of entries
     * @param size the size of the page in number of entries
     * @param searchTerms a search term (usually "*")
     * @return a Stream of JsonNode for using with java 8 streams.
     */
    private Stream<JsonNode> getPaginatedResultsAsStream(@NonNull String indexDate, @NonNull Long from, @NonNull Integer size, String searchTerms) {
        return StreamSupport
                .stream(getPaginatedResults(indexDate, from, size, searchTerms)
                        .path("hits")
                        .spliterator(), true);
    }


    /**
     * Given a JsonNode, pretty print it to String using Jackson's ObjectMapper
     * @param node a valid JsonNode object
     * @return a String containing a pretty printed version of the JsonNode
     */
    public String toPrettyString(JsonNode node) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }


    /**
     * Given a Json _source node (elasticsearch format) apply all regular expressions using
     * ReplacementService which reads regexs from expressions.txt file
     * This is an internal method not intended for public use.
     * @param sourceNode a JsonNode with _source structure from elasticsearch.
     * @return a modified JsonNode with message node altered by all these rules.
     */
    private JsonNode applyRegularExpressions(JsonNode sourceNode) {

        String message = sourceNode.get("message").asText();
        String alteredMessage = replacementsService.applyAllReplacements(message);

        ObjectNode mutableNode = (ObjectNode) sourceNode;
        mutableNode.put("message", alteredMessage);
        return sourceNode;
    }

    /**
     * Given a Json _source node (elasticsearch format) adds a "bytes" node containing the size
     * of the "message" node in bytes
     * This is an internal method not intended for public use.
     * @param sourceNode
     * @return modified Json _source node
     */
    public JsonNode addSizeOfMessage(JsonNode sourceNode) {

        String message = sourceNode.get("message").asText();
        int len = message.getBytes().length;
        ObjectNode mutableNode = (ObjectNode) sourceNode;
        mutableNode.put("bytes", len);
        return sourceNode;
    }


    /**
     * Extracts the message node from a _source node in elasticsearch Json response structure
     * Internal method not intended for public use.
     * @param sourceNode
     * @return the String from the message node.
     */
    private String getMessageFromNode(JsonNode sourceNode) {
        return sourceNode.get("message").asText();
    }


    /**
     * Given a _source node from elasticsearch structure replace all numbers in message
     * with generic digits
     * @param sourceNode a JsonNode containing the _source structure
     * @param minDigits minimun number of digits for replacement. Avoids replacing single digits
     * @return A modified source structure with message node mutated.
     */
    private JsonNode generifyNumbers(JsonNode sourceNode, int minDigits) {
        String message = sourceNode.get("message").asText();
        String modifiedMessage = Utils.generalizeNumbersInMessage(message, 2);

        ObjectNode mutableNode = (ObjectNode) sourceNode;
        mutableNode.put("message", modifiedMessage);
        return sourceNode;
    }

    /**
     * Given a <i>_source node</i> from elasticsearch structure, search values of given tags[]
     * inside the <i>tags</i> node and replace these values inside the <i>message</i> node
     * with either:
     *  - A generic [tag:TAGNAME]
     *  - A generic [tag:value] whose numbers have been converted to 9's
     * @param sourceNode a JsonNode containing the _source structure
     * @param tags an array of tag names
     * @param removeValue either to replace with tagname (true) or generic numbers (false)
     * @return a modified source structure with message node mutated.
     */
    private JsonNode replaceTags(JsonNode sourceNode, String[] tags, boolean removeValue) {

        // Obtengo el mensaje original
        String message = sourceNode.get("message").asText();
        ObjectNode mutableNode = (ObjectNode) sourceNode;

        // Tratamiento de tags
        for (String tag : tags) {
            JsonNode tagNode = sourceNode.get("tags").get(tag);
            if (tagNode != null) {
                // Si el tag esta en ._source.tags, elimino el texto del .message
                String tagValue = tagNode.asText();
                if (tagValue != null && !tagValue.isEmpty()) {
                    // La regex contempla espacios en blanco opcionales
                    Pattern p = Pattern.compile("\\[ ?" + tag + " ?: ?" + tagValue + " ?\\]");
                    Matcher m = p.matcher(message);
                    // Reemplazo el valor del TAG por su NOMBRE
                    if (removeValue) {
                        message = m.replaceFirst(format("[%s:%s]", tag, tag.toUpperCase()));
                    } else if (Utils.isNumeric(tagValue)) {
                        String replacedNumber = Utils.generalizeNumber(tagValue);
                        message = m.replaceFirst(format("[%s:%s]", tag, replacedNumber));
                    } else {
                        String replacedWord = Utils.generalize(tagValue);
                        message = m.replaceFirst(format("[%s:%s]", tag, replacedWord));
                    }

                }
            }
        }
        mutableNode.put("message", message);
        return sourceNode;
    }


    /**
     * Given a set of results obtained from elasticsearch, get all tags inside the _source nodes
     * and sample one value for each one. Then print the list to stdout.
     * @param resultados a List of JsonNode containing the results from elasticsearch
     */
    private void dumpTags(List<JsonNode> resultados) {

        System.out.println("Tags sampleados:\n");
        Map<String, String> sampledTags = new HashMap<>();

        for (JsonNode resultado : resultados) {
            Map<String, String> tags = mapper.convertValue(resultado.path("tags"), new TypeReference<Map<String, String>>() {
            });
            tags.entrySet().forEach(e -> {
                if (!sampledTags.containsKey(e.getKey())) {
                    sampledTags.put(e.getKey(), e.getValue());
                }
            });
        }

        sampledTags.entrySet().stream()
                .map( x -> format("\t%s: %s", x.getKey(), x.getValue()))
                .forEach(System.out::println);
    }

    /**
     * Given a List of JsonNode containing results from elasticsearch, calculate overall stats
     * over the sample such as total lines read, bytes read, bytes after reduction, and compression rate.
     * @param resultados a List of JsonNode containing results from elasticsearch
     */
    private void printStatistics(List<JsonNode> resultados) {
        Long bytes_before = getTotalBytesBeforeReplaces(resultados);
        Long bytes_after = getTotalBytesAfterReplaces(resultados);

        Double ratio = bytes_after / bytes_before.doubleValue();

        System.out.println(format("Lines leidas             : %d", resultados.size()));
        System.out.println(format("Bytes mensajes originales: %d", bytes_before));
        System.out.println(format("Bytes mensajes reducidos : %d", bytes_after));
        System.out.println(format("Ratio de compresion      : %.2f", ratio));
    }

    /**
     * Given a List of JsonNode containing results from elasticsearch,
     * reduce the total sum of bytes from message nodes in its current state and return the total as Long
     * @param resultados a List of JsonNode containing results from elasticsearch
     * @return a Long holding the total bytes
     */
    private Long getTotalBytesAfterReplaces(List<JsonNode> resultados) {
        return resultados
                    .stream()
                    .map(it -> it.path("message").asText().getBytes().length).reduce(0, (x, y) -> (x + y)).longValue();
    }


    /**
     * Given a List of JsonNode containing results from elasticsearch,
     * reduce the total sum of bytes from original message nodes and return the total as Long
     * @param resultados a List of JsonNode containing results from elasticsearch
     * @return a Long holding the total bytes
     */
    private Long getTotalBytesBeforeReplaces(List<JsonNode> resultados) {
        return resultados
                    .stream()
                    .map(it -> it.path("bytes").longValue()).reduce(0L, (x, y) -> x + y);
    }


    /**
     * Given a sorted rank of String and Long values (as map String:Long)
     * dump the first <code>maxLines</code> lines of the rank to stdout
     * @param sortedRank a sorted {@code Map<String, Long>} using message as key
     * @param maxLines the max number of lines to display in stdout.
     */
    private void dumpSortedRank(Map<String, Long> sortedRank, int maxLines){
        System.out.println(format("Ranking por count de lines (limitado a %d lineas):\n\n", maxLines));
        sortedRank.entrySet().stream().limit(maxLines).forEach(e->{
            System.out.println(format("\t%d: %s",e.getValue(), e.getKey()));
        });
        System.out.println("\n");
    }

    /**
     * Given a sorted rank of String and Long values (as map String:Long)
     * write the rank to <code>fileName</code> file using the <code>totalSum</code> for calculating percentages
     * @param sortedRank a sorted {@code Map<String, Long>} using message as key
     * @param fileName name of the file to write output to
     * @param totalSum total value to calculate % of each individual value agains the total
     */
    private void writeSortedRankToFile(Map<String, Long> sortedRank, String fileName, Long totalSum) {

        System.out.println(format("Escribiendo %s...",fileName));

        try {
            Files.write(Paths.get(fileName), (Iterable<String>)sortedRank
                    .entrySet().stream().map(e-> {
                        Long value = e.getValue();

                        BigDecimal pctg = BigDecimal.valueOf((double)value * 100L / totalSum).setScale(2, RoundingMode.HALF_UP);

                        return format("%d (%.2f%%): %s", value, pctg, e.getKey());
                    })::iterator);
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println(format("Fin escritura %s.", fileName));
    }

    /**
     * Given a list of _source nodes rank the lines grouping by message
     * and return a {@Code Map<String, Long>} for each message containing the line count
     * @param nodes List of {@Code JsonNode}
     * @return a sorted ranked count of each line
     */
    private Map<String, Long> countMessagesDesc(List<JsonNode> nodes) {
        Map<String, Long> messageRank = nodes.stream()
                .parallel()
                .map(x -> x.path("message").asText())
                .collect(groupingBy(Function.identity(), counting()));

        return messageRank.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (v1, v2) -> {
                            throw new IllegalStateException();
                        },
                        LinkedHashMap::new
                ));
    }



    /**
     * Given a list of _source nodes from elasticsearch results,
     * weight each message by original message size and generate a rank
     * grouped by message.
     * @param nodes a List of JsonNode containing the _source nodes
     * @return a Map whose keys are the messages and values the sum of bytes for each message
     */
    Map<String, Long> weightMessagesBySize(List<JsonNode> nodes){
        Map<String, Long> messageRank = nodes.stream()
                .map( x->{
                    Long size = x.path("bytes").asLong();
                    String message = x.path("message").asText();
                    String source = x.path("tags").path("source").asText("");
                    return new LogMessage(message, source,size);
                }).collect(groupingBy(LogMessage::getMessage,
                        summingLong(LogMessage::getSize)));

        return messageRank.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> {
                    throw new IllegalStateException();
                },
                        LinkedHashMap::new));

    }


    /**
     * Given a list of _source nodes from elasticsearch results,
     * count messages for each source (tags.source) and generate a rank
     * @param nodes a List of JsonNode containing the _source nodes
     * @return a Map whose keys are the source tags and values the count of messages
     */
    private Map<String, Long> countMessagesBySource(List<JsonNode> nodes){
        Map<String, Long> countBySource = nodes.stream()
                .parallel()
                .map(x -> {
                    String source = "";
                    JsonNode tags = x.path("tags");
                    if (tags != null) {
                        source = tags.path("source").asText("NO_SOURCE");
                    }

                    return source;
                }).collect(groupingBy(Function.identity(), counting()));

        return countBySource.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (v1, v2) -> {
                            throw new IllegalStateException();
                        },
                        LinkedHashMap::new
                ));
    }


    /**
     * Given a list of _source nodes from elasticsearch results,
     * group by each tag.source and sum original message size generating a rank
     * @param nodes a List of JsonNode containing the _source nodes
     * @return a Map whose keys are the source.tags and values the sum of bytes of its messages
     */
    Map<String, Long> weightMessagesBySource(List<JsonNode> nodes){
        Map<String, Long> messageRank = nodes.stream()
                .map(x -> {
                    Long size = x.path("bytes").asLong();
                    String message = x.path("message").asText();
                    String source = x.path("tags").path("source").asText("NO_SOURCE");
                    return new LogMessage(message, source, size);
                }).collect(groupingBy(LogMessage::getSource,
                        summingLong(LogMessage::getSize)));

        return messageRank.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> {
                            throw new IllegalStateException();
                        },
                        LinkedHashMap::new));
    }

    /**
     * Given a list of _source nodes from elasticsearch results,
     * count messages for each scope (tags.scope) and generate a rank
     * @param nodes a List of JsonNode containing the _source nodes
     * @return a Map whose keys are the source tags and values the count of messages
     */
    private Map<String, Long> countMessagesByScope(List<JsonNode> nodes){
        Map<String, Long> countByScope = nodes.stream()
                .parallel()
                .map(x -> {
                    String scope = "";
                    JsonNode tags = x.path("tags");
                    if (tags != null) {
                        scope = tags.path("scope").asText("NO_SCOPE");
                    }

                    return scope;
                }).collect(groupingBy(Function.identity(), counting()));

        return countByScope.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (v1, v2) -> {
                            throw new IllegalStateException();
                        },
                        LinkedHashMap::new
                ));
    }


    /**
     * Given a list of _source nodes from elasticsearch results,
     * group by each tag.sscope and sum original message size generating a rank
     * @param nodes a List of JsonNode containing the _source nodes
     * @return a Map whose keys are the source.tags and values the sum of bytes of its messages
     */
    Map<String, Long> weightMessagesByScope(List<JsonNode> nodes){
        Map<String, Long> messageRank = nodes.stream()
                .map(x -> {
                    Long size = x.path("bytes").asLong();
                    String message = x.path("message").asText();
                    String source = x.path("tags").path("scope").asText("NO_SCOPE");
                    return new LogMessage(message, source, size);
                }).collect(groupingBy(LogMessage::getSource,
                        summingLong(LogMessage::getSize)));

        return messageRank.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> {
                            throw new IllegalStateException();
                        },
                        LinkedHashMap::new));
    }

}
