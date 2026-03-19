package com.example.extractor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ConfigLoader {

    public static Map<String, String> loadProperties(String rootPath) {
        Map<String, String> map = new HashMap<>();

        try {
            Files.walk(Paths.get(rootPath))
                    .filter(p -> p.toString().endsWith(".properties"))
                    .forEach(path -> {
                        try (InputStream is = Files.newInputStream(path)) {
                            Properties props = new Properties();
                            props.load(is);

                            props.forEach((k, v) -> map.put(k.toString(), v.toString()));

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }

        return map;
    }

    public static Map<String, String> loadYaml(String rootPath) {
        Map<String, String> map = new HashMap<>();

        try {
            Files.walk(Paths.get(rootPath))
                    .filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                    .forEach(path -> {
                        try (InputStream is = Files.newInputStream(path)) {

                            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
                            Map<String, Object> obj = yaml.load(is);

                            flatten("", obj, map);

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }

        return map;
    }

    private static void flatten(String prefix, Map<String, Object> source, Map<String, String> target) {

        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();

            if (entry.getValue() instanceof Map) {
                flatten(key, (Map<String, Object>) entry.getValue(), target);
            } else {
                target.put(key, String.valueOf(entry.getValue()));
            }
        }
    }
}