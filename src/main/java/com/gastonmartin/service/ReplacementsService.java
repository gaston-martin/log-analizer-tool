package com.gastonmartin.service;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ReplacementsService {
    Path expressionsPath;

    public ReplacementsService() {
        try {
            expressionsPath = Paths.get(ClassLoader.getSystemResource("expressions.txt").toURI());

        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        // Fallar rapido si alguna regex no compila
        checkExpressions();
    }

    private  List<String[]> readExpressions(){
        try {
            return Files
                    .lines(expressionsPath, Charset.defaultCharset())
                    .filter( x-> !x.trim().isEmpty())
                    .filter( x-> !x.startsWith("#"))
                    .map(line -> line.split("\\|"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void checkExpressions(){
        try{
            Files
                    .lines(expressionsPath, Charset.defaultCharset())
                    .filter( x-> !x.trim().isEmpty())
                    .filter( x-> !x.startsWith("#"))
                    .map(line -> {
                        String[] parts = line.split("\\|");
                        if (parts.length != 2) {
                            System.err.println("ERROR: reading expressions, line:\n" + line + "\nExpected 2 expressions separated by |, but got " + parts.length);
                            throw new RuntimeException("unexpected number of expressions");
                        }
                        return parts[0];
                    }).forEach( pattern -> {
                        // Con esto pruebo si el pattern es valido.
                        Pattern.compile(pattern);
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String replace(String pattern, String replacement, String line){

        Pattern r = Pattern.compile(pattern);
        Matcher m = r.matcher(line);
        String s = m.replaceAll(replacement);
        return s;
    }

    public String applyAllReplacements(String line){

        for (String[] exp : readExpressions()) {
            String regexp = exp[0];
            String replacement = exp[1];
            line = replace(regexp, replacement, line);
        }
        return line;
    }
}
