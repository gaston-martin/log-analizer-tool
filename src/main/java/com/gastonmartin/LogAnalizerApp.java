package com.gastonmartin;


import com.gastonmartin.service.LogProcessorService;
import org.apache.commons.cli.*;

import java.util.Arrays;
import java.util.stream.Collectors;

public class LogAnalizerApp

{
    public static void main( String[] args ) {

        String searchTerms;
        CommandLine cli = parseArgs(args);
        if (cli.hasOption("i")) {
            searchTerms = Arrays.asList(cli.getOptionValue("i").split(","))
                    .stream()
                    .map(i -> String.format("tags.instance_id:%s", i))
                    .collect(Collectors.joining(" OR "));


            System.out.println(searchTerms);
            //searchTerms = String.format("tags.instance_id:%s", cli.getOptionValue("i"));
        } else if (cli.hasOption("s")) {
            searchTerms = cli.getOptionValue("s");
            //todo: Concatenar con URLEncode ?
        } else if (cli.hasOption("v")) {
            searchTerms = String.format("tags.version:%s", cli.getOptionValue("v"));

        } else {
            searchTerms = "*";
        }

        if (cli.hasOption("f") || cli.hasOption("t")){
            String from = cli.getOptionValue("f");
            String to = cli.getOptionValue("t");

            if ( from == null || from.trim().equals("")) from = "*";
            if ( to == null || to.trim().equals("")) to = "*";
            String range = String.format("tags.date:[%s TO %s]",from,to);
            searchTerms = String.format("%s AND %s",searchTerms, range);
        }

        LogProcessorService lps = new LogProcessorService();
        if (cli.hasOption("z")) {
            int maxResults = Integer.valueOf(cli.getOptionValue("z"));
            if (maxResults > 0 ) lps.setMaxResults(maxResults);
        }
        lps.process(null, searchTerms); // Use default (today's index)

    }

    private static CommandLine parseArgs(String[] args){

        CommandLine commandLine;
        Option instanceId = Option.builder("i")
                .required(false)
                .desc("instance id(s) (comma-separated)")
                .longOpt("instance_ids")
                .numberOfArgs(1)
                .type(String.class)
                .build();

        Option searchTerms = Option.builder("s")
                .required(false)
                .desc("search terms")
                .longOpt("search")
                .numberOfArgs(1)
                .type(String.class)
                .build();

        Option sampleSize = Option.builder("z")
                .required(false)
                .desc("sample size (default 3000)")
                .longOpt("size")
                .numberOfArgs(1)
                .type(Integer.class)
                .build();


        Option version = Option.builder("v")
                .required(false)
                .desc("specific version")
                .longOpt("version")
                .numberOfArgs(1)
                .type(String.class)
                .build();

        Option from = Option.builder("f")
                .required(false)
                .desc("from date (i.e 2020-05-19_00:00:00)")
                .longOpt("from")
                .numberOfArgs(1)
                .type(String.class)
                .build();

        Option to = Option.builder("t")
                .required(false)
                .desc("to date (i.e 2020-05-19_00:00:00)")
                .longOpt("to")
                .numberOfArgs(1)
                .type(String.class)
                .build();

        Option help = Option.builder("h")
                .required(false)
                .desc("get help")
                .longOpt("help")
                .build();

        Options options = new Options();
        CommandLineParser parser = new DefaultParser();

        options.addOption(instanceId);
        options.addOption(version);
        options.addOption(searchTerms);
        options.addOption(from);
        options.addOption(to);
        options.addOption(sampleSize);
        options.addOption(help);

        try
        {
            commandLine = parser.parse(options, args);

            if (commandLine.hasOption("h")){
                String header = "\n";
                String footer = "\nConnects to elasticsearch and samples logs and build a ranking of log usage";
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("LogAnalizer", header, options, footer, true);
                System.exit(0);
            }
        }
        catch (ParseException exception)
        {
            System.out.println(exception.getMessage());
            throw new RuntimeException(exception);
        }
        return commandLine;
    }
}
