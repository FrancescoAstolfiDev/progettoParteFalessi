package project.utils;

import java.util.ArrayList;
import java.util.List;

public enum WhatIf{
        A_MATRIX("a_matrix"),
        B_MATRIX("b_matrix"),
        B_PLUS_MATRIX("b_plus_matrix"),
        C_MATRIX("c_matrix"),;
        private String name;
        WhatIf(String name){
            this.name=name;
        }
        public String getName(){
            return name;
        }
        public static  List<String> getListMatrix(){
            List<String> listMatrix=new ArrayList<>();
            for(WhatIf whatIf:WhatIf.values()){
                listMatrix.add(whatIf.getName());
            }
            return listMatrix;
        }
    }