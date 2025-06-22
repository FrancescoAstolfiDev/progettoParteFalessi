package project.utils;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ConstantsWindowsFormat {
    ConstantsWindowsFormat(){

    }
    // depends on the base of the project
    public static final Path BASE_PATH = Paths.get("C:\\isw2\\progettoParteFalessi\\My_Ispw2_Project_Falessi");
    public static final Path CSV_PATH = BASE_PATH.resolve("csv");
    public static final Path PARTIALS_CSV_PATH = CSV_PATH.resolve("partials");
    public static final Path RULES_SET_PATH = BASE_PATH.resolve("config").resolve("pmd").resolve("custom_rules.xml");
    public static final Path CACHE_PATH = BASE_PATH.resolve("cache");
    public static final Path TEST_CSV_PATH=CSV_PATH.resolve("tests");
    // depends where are the clone
    public static final Path REPO_CLONE_PATH =Paths.get("C:\\isw2\\progetti_clonati\\");



}
