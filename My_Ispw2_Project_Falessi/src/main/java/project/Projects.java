package project;


public enum Projects {
    BOOKKEEPER("bookkeeper"),
    OPENJPA("openjpa");
    private final String label;
    private double split;
    Projects(String label ){
        this.label=label;
        if(label.equals("bookkeeper")){
            split=  50/100.0 ;
        }else{
            split=  33/100.0;
        }
    }
    public double getSplit() {
        return split;
    }

    @Override
    public String toString() {
        return label;
    }
}