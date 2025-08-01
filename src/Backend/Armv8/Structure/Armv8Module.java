package Backend.Armv8.Structure;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import Backend.Armv8.tools.Armv8MyLib;  

public class Armv8Module {
    private final ArrayList<Armv8GlobalVariable> dataGlobalVariables = new ArrayList<>();
    private final ArrayList<Armv8GlobalVariable> bssGlobalVariables = new ArrayList<>();
    private final LinkedHashMap<String, Armv8Function> functions;

    public Armv8Module() {
        this.functions = new LinkedHashMap<>();
    }

    public void addFunction(String functionName, Armv8Function function) {
        this.functions.put(functionName, function);
    }

    public ArrayList<Armv8GlobalVariable> getDataGlobalVariables() {
        return this.dataGlobalVariables;
    }

    public Armv8Function getFunction(String functionName) {
        return functions.get(functionName);
    }

    public LinkedHashMap<String, Armv8Function> getFunctions() {
        return this.functions;
    }

    public void addDataVar(Armv8GlobalVariable var) {
        dataGlobalVariables.add(var);
    }

    public void addBssVar(Armv8GlobalVariable var) {
        bssGlobalVariables.add(var);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(".arch armv8-a\n\n");
        
        if(!dataGlobalVariables.isEmpty()) {
            sb.append(".data\n");
            sb.append(".align\t3\n");
            for (Armv8GlobalVariable globalVariable : dataGlobalVariables) {
                sb.append(globalVariable.dump());
            }
        }

        if(!bssGlobalVariables.isEmpty()) {
            sb.append(".bss\n");
            sb.append(".align\t3\n");
            for (Armv8GlobalVariable globalVariable : bssGlobalVariables) {
                sb.append(globalVariable.dump());
            }
        }
        
        sb.append(".text\n");
        // for(Armv8Function function:functions.values()){
        //     if(function.getName().equals("main")){
        //         sb.append(function.dump());
        //         break;
        //     }
        // }
        

        for(Armv8Function function:functions.values()){
            // if(!(function.getName().equals("main")))
            sb.append(function.dump());
        }
        sb.append("\n\n\n\n\n\n");
        sb.append(Armv8MyLib.generateMemsetFunction());
        sb.append(Armv8MyLib.generateGetarray64Function());
        sb.append(Armv8MyLib.generatePutarray64Function());
        sb.append(Armv8MyLib.generateGetfarray64Function());
        sb.append(Armv8MyLib.generatePutfarray64Function());

    
        return sb.toString();
    }
} 