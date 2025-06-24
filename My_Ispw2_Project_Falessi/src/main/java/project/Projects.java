package project;


public enum Projects {
    BOOKKEEPER("Bookkeeper"),
    OPENJPA("Openjpa");
    private final String label;
    private double split;
    Projects(String label ){
        this.label=label;
        if(label.equals("Bookkeeper")){
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