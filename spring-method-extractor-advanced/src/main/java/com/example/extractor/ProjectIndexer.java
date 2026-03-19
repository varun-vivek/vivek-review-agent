
package com.example.extractor;

import com.github.javaparser.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.File;
import java.nio.file.*;
import java.util.*;

public class ProjectIndexer {
    public Map<String, String> classToFilePath = new HashMap<>();

    Map<String, String> configMap = new HashMap<>();

    private String root;
    private List<CompilationUnit> units = new ArrayList<>();

    public ProjectIndexer(String root) {
        this.root = root;
    }

    public void buildIndex() throws Exception {

        configMap.putAll(ConfigLoader.loadProperties(root));
        configMap.putAll(ConfigLoader.loadYaml(root));
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());

        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_18)
                .setSymbolResolver(new JavaSymbolSolver(solver));

        StaticJavaParser.setConfiguration(config);
        Files.walk(Paths.get(root))
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        CompilationUnit cu = StaticJavaParser.parse(path);

                        cu.findAll(ClassOrInterfaceDeclaration.class)
                                .forEach(cls -> {
                                    String className = cls.getNameAsString();
                                    classToFilePath.put(className, path.toString());
                                });

                        units.add(cu);

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
    }

    public List<MethodMeta> extractAllMethods() {
        List<MethodMeta> list = new ArrayList<>();

        for (CompilationUnit cu : units) {
            cu.findAll(MethodDeclaration.class).forEach(m -> {
                list.add(AdvancedExtractor.extract(m, cu, classToFilePath, configMap));
            });
        }

        return list;
    }
}
