
package com.example.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.*;
import java.util.*;

public class Main {

    public static void main(String[] args) throws Exception {
        String projectPath = args.length > 0 ? args[0] : "src/main/java";

        ProjectIndexer indexer = new ProjectIndexer(projectPath);
        indexer.buildIndex();

        List<MethodMeta> result = indexer.extractAllMethods();

        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter()
              .writeValue(new File("output.json"), result);

        System.out.println("✅ Extraction completed");
    }
}
