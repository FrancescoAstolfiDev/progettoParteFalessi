package project.utils;

public class ConstantSize {


    private ConstantSize() {

    }
    public static final double SPLIT_PERCENTAGE=33.0/100;
    public static final int NUM_COMMITS = 10000;
    public static final int MAX_BUGGY_PERCETAGE = 10;

    // Maximum cache file size in bytes (90MB)
    public static final long MAX_CACHE_FILE_SIZE = 90 * 1024 * 1024;

    public static final int FREQUENCY_LOG =10;
    public static final int FREQUENCY_WRITE_CACHE =20;
    public static final int FREQUENCY_WRITE_CSV =100;
    public static final int NUM_THREADS =3;

    // Number of commits to prioritize by size
    public static final int LAST_COMMITS_TO_PRIORITIZE = 20;

    // Maximum number of classes per commit
    public static final int MAX_CLASSES_PER_COMMIT = 50;

}
