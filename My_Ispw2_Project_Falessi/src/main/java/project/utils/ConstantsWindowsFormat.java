package project.utils;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ConstantsWindowsFormat {
    // depends on the base of the project
    public static final Path basePath= Paths.get("C:\\isw2\\progettoParteFalessi\\progettoParteFalessi\\My_Ispw2_Project_Falessi");
    public static final Path csvPath= basePath.resolve("csv");
    public static final Path rulesSetPath =basePath.resolve("config").resolve("pmd").resolve("custom_rules.xml");
    public static final Path cachePath =basePath.resolve("cache");
    // depends where are the clone
    public static final Path repoClonePath=Paths.get("C:\\isw2\\progetti_clonati\\");

}
