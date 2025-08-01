package Backend.Structure;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import Backend.Utils.AArch64MyLib;  

public class AArch64Module {
    private final ArrayList<AArch64GlobalVariable> dataGlobalVariables = new ArrayList<>();
    private final ArrayList<AArch64GlobalVariable> bssGlobalVariables = new ArrayList<>();
    private final LinkedHashMap<String, AArch64Function> functions;

    public AArch64Module() {
        this.functions = new LinkedHashMap<>();
    }

    public void addFunction(String functionName, AArch64Function function) {
        this.functions.put(functionName, function);
    }

    public ArrayList<AArch64GlobalVariable> getDataGlobalVariables() {
        return this.dataGlobalVariables;
    }

    public AArch64Function getFunction(String functionName) {
        return functions.get(functionName);
    }

    public LinkedHashMap<String, AArch64Function> getFunctions() {
        return this.functions;
    }

    public void addDataVar(AArch64GlobalVariable var) {
        dataGlobalVariables.add(var);
    }

    public void addBssVar(AArch64GlobalVariable var) {
        bssGlobalVariables.add(var);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(".arch armv8-a\n\n");
        
        if(!dataGlobalVariables.isEmpty()) {
            sb.append(".data\n");
            sb.append(".align\t3\n");
            for (AArch64GlobalVariable globalVariable : dataGlobalVariables) {
                sb.append(globalVariable.dump());
            }
        }

        if(!bssGlobalVariables.isEmpty()) {
            sb.append(".bss\n");
            sb.append(".align\t3\n");
            for (AArch64GlobalVariable globalVariable : bssGlobalVariables) {
                sb.append(globalVariable.dump());
            }
        }
        
        sb.append(".text\n");
        // for(AArch64Function function:functions.values()){
        //     if(function.getName().equals("main")){
        //         sb.append(function.dump());
        //         break;
        //     }
        // }
        

        for(AArch64Function function:functions.values()){
            // if(!(function.getName().equals("main")))
            sb.append(function.dump());
        }
        sb.append("\n\n\n\n\n\n");
        sb.append(AArch64MyLib.generateMemsetFunction());
        sb.append(AArch64MyLib.generateGetarray64Function());
        sb.append(AArch64MyLib.generatePutarray64Function());
        sb.append(AArch64MyLib.generateGetfarray64Function());
        sb.append(AArch64MyLib.generatePutfarray64Function());

    
        return sb.toString();
    }
} 