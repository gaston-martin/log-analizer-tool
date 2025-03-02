package com.gastonmartin.util;

import org.apache.commons.codec.digest.DigestUtils;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.math.NumberUtils.isCreatable;

public class Utils {

    /**
     * Evaluates if a given String is convertable to a Number as per the NumberUtils library
     * @param number as a String
     * @return true or false
     */
    public static boolean isNumeric(String number){
        if (number == null) return false;
        return isCreatable(number);
    }

    /**
     * Evaluates if a given String is convertable to a Number as per the NumberUtils library, ignoring comma
     * This is relevant for some logs containing numbers separated by comma
     * @param number as a String
     * @return true or false
     */
    public static boolean isNumericIgnoringComma(String number){
        if (number == null) return false;
        return isCreatable(number.replaceAll(",","").replaceAll("\\s",""));
    }

    /**
     * Evaluates if a given number (as a String) contains a decimal point
     * @param number as a String
     * @return true or false
     */
    public static boolean isFractional(String number){
        if (number == null) return false;
        if (number.contains(".")) return true;
        return false;
    }

    /**
     * Given a number as a String replaces its digits with 9's
     * This method allows all numbers with same number of digits to be generalized
     * @param aNumber as a String
     * @return another number built upon concatenation of character '9'
     */
    public static String generalizeNumber(String aNumber){
        return aNumber.replaceAll("[0-9]","9");
    }

    /**
     * Given a String replaces al words with "x" characters
     * @param string the input String
     * @return words containing only "x" characters
     */
    public static String generalize(String string){
        return string.replaceAll("\\w","x");
    }

    /**
     * Given a whole line of message this method will tokenize the words of the message
     * and <b>generalize numbers</b> with some heuristics such as splitting the words again over
     * other separators (: and -) to handle words like userid:12345 or id-12345
     * This is the <b>main generalize function</b> which calls the 2nd version suited for <b>recursion</b> (see below)
     * @param message The whole line of the message
     * @param minDigits Minimun digits to generalize (i.e. 2 to avoid replacing single digits)
     * @return a new String with numbers generalized as per the called generalization methods
     */
    public static String generalizeNumbersInMessage(String message, int minDigits){
        return generalizeNumbersInMessage(message,minDigits," ");
    }

    /**
     * Given a whole line of message this method will tokenize the words of the message
     * and generalize numbers with some heuristics such as splitting the words again over
     * other separators (: and -) to handle words like userid:12345 or id-12345
     * This is the <b>recursive version</b> of the same function presented above
     * @param message The whole line of the message
     * @param minDigits Minimun digits to generalize (i.e. 2 to avoid replacing single digits)
     * @param separator Used by the recursion to split on : or - rather than space
     * @return a new String with numbers generalized as per the called generalization methods
     */
    public static String generalizeNumbersInMessage(String message, int minDigits, String separator) {

        return Arrays.stream(message.split(separator))
                .map(word -> {
                    if (Utils.isNumericIgnoringComma(word)) {
                        if (isFractional(word)) {
                            return word.replaceAll("[0-9]", "9");
                        } else {
                            int numberOfNumbers = word.replaceAll("\\D", "").length();
                            if (numberOfNumbers >= minDigits) {
                                return word.replaceAll("\\d", "9");
                            }
                        }
                    } else if (word.contains(":") && !word.endsWith(":")) {
                        return generalizeNumbersInMessage(word, minDigits, ":");
                    } else if (!word.startsWith("-") && word.contains("-")) {
                        return generalizeNumbersInMessage(word, minDigits, "-");
                    }

                    return word;
                })
                .collect(Collectors.joining(separator));
    }

    /**
     * Given a message generate a HASH representing the message.
     * This has been added for indexing messages in large collections
     * @param message to be hash
     * @return a hash representing the message
     */
    public static String getHashForString(String message){
        // Se eligio este algoritmo por ninguna razon en particular.
        return DigestUtils.sha256Hex(message);
    }

    /**
     * Calculates the name for today's elasticsearch index based on current date
     * @return a String representing the current date such as 2020.05.30
     */
    public static String getTodayIndexName(){
        SimpleDateFormat sdf = new SimpleDateFormat("YYYY.MM.dd");
        return sdf.format(Calendar.getInstance().getTime());
    }
}
