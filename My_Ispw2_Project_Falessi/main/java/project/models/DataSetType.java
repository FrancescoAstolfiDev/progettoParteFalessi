package project.models;

public enum DataSetType {
    PARTIAL("Partial"),
    TRAINING("Train"),
    TEST("Test");

    private final String label;

    DataSetType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}