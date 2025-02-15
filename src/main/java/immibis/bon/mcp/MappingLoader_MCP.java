package immibis.bon.mcp;

import immibis.bon.IProgressListener;
import immibis.bon.Mapping;
import immibis.bon.NameSet;
import immibis.bon.mcp.MinecraftNameSet.Side;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class MappingLoader_MCP {

    public static class CantLoadMCPMappingException extends Exception {
        private static final long serialVersionUID = 1;

        public CantLoadMCPMappingException(String reason) {
            super(reason);
        }
    }


    // forward: obf -> searge -> mcp
    // reverse: mcp -> searge -> obf
    private Mapping forwardSRG, reverseSRG, forwardCSV, reverseCSV;


    private final Map<String, Set<String>> srgMethodOwnersAndDescs = new HashMap<>(); // SRG name -> SRG owners
    private final Map<String, Set<String>> srgFieldOwners = new HashMap<>(); // SRG name -> SRG owners

    private ExcFile excFileData;

    public MappingLoader_MCP() {
    }

    @Deprecated
    public MappingLoader_MCP(String mcVer, Side side, File mcpDir, IProgressListener progress) throws IOException {
        File srgFile, excFile;
        int[] sideNumbers;

        switch (side) {
        case UNIVERSAL:
            sideNumbers = new int[]{2, 1, 0};
            if (new File(mcpDir, "conf/packaged.srg").exists()) {
                srgFile = new File(mcpDir, "conf/packaged.srg");
                excFile = new File(mcpDir, "conf/packaged.exc");
            } else {
                srgFile = new File(mcpDir, "conf/joined.srg");
                excFile = new File(mcpDir, "conf/joined.exc");
            }
            break;

        case CLIENT:
            sideNumbers = new int[]{0};
            srgFile = new File(mcpDir, "conf/client.srg");

            if (new File(mcpDir, "conf/joined.exc").exists())
                excFile = new File(mcpDir, "conf/joined.exc");
            else
                excFile = new File(mcpDir, "conf/client.exc");

            break;

        case SERVER:
            sideNumbers = new int[]{1};
            srgFile = new File(mcpDir, "conf/server.srg");

            if (new File(mcpDir, "conf/joined.exc").exists())
                excFile = new File(mcpDir, "conf/joined.exc");
            else
                excFile = new File(mcpDir, "conf/server.exc");

            break;

        default:
            throw new AssertionError("side is " + side);
        }

        SrgFile srg_File = new SrgFile(srgFile, false);
        Map<String, String> fieldNames;
        Map<String, String> methodNames;
        if (srg_File.oldStyle) {
            fieldNames = srg_File.SRGtoMCPfields;
            methodNames = srg_File.SRGtoMCPmethods;
        } else {
            fieldNames = CsvFile.read(new File(mcpDir, "conf/fields.csv"), sideNumbers);
            methodNames = CsvFile.read(new File(mcpDir, "conf/methods.csv"), sideNumbers);
        }
        load(side, mcVer, ExcFile.read(excFile), srg_File, fieldNames, methodNames, progress);
    }

    public void load(Side side, String mcVer, ExcFile excFile, SrgFile srgFile, Map<String, String> fieldNames,
                     Map<String, String> methodNames, IProgressListener progress) {

        NameSet obfNS = new MinecraftNameSet(MinecraftNameSet.Type.OBF, side, mcVer);
        NameSet srgNS = new MinecraftNameSet(MinecraftNameSet.Type.SRG, side, mcVer);
        NameSet mcpNS = new MinecraftNameSet(MinecraftNameSet.Type.MCP, side, mcVer);

        forwardSRG = new Mapping(obfNS, srgNS, excFile.oldStyle);
        reverseSRG = new Mapping(srgNS, obfNS, excFile.oldStyle);

        forwardCSV = new Mapping(srgNS, mcpNS, excFile.oldStyle);
        reverseCSV = new Mapping(mcpNS, srgNS, excFile.oldStyle);

        if (progress != null) {
            progress.setMax(3);
            progress.set(0);
        }
        excFileData = excFile;
        if (progress != null) progress.set(1);
        loadSRGMapping(srgFile);
        if (progress != null) progress.set(2);
        loadCSVMapping(fieldNames, methodNames);
    }


    private void loadSRGMapping(SrgFile srg) {
        forwardSRG.setDefaultPackage("net/minecraft/src/");
        reverseSRG.addPrefix("net/minecraft/src/", "");

        for (Map.Entry<String, String> entry : srg.classes.entrySet()) {
            String obfClass = entry.getKey();
            String srgClass = entry.getValue();

            forwardSRG.setClass(obfClass, srgClass);
            reverseSRG.setClass(srgClass, obfClass);
        }

        for (Map.Entry<String, String> entry : srg.fields.entrySet()) {
            String obfOwnerAndName = entry.getKey();
            String srgName = entry.getValue();

            String obfOwner = obfOwnerAndName.substring(0, obfOwnerAndName.lastIndexOf('/'));
            String obfName = obfOwnerAndName.substring(obfOwnerAndName.lastIndexOf('/') + 1);

            String srgOwner = srg.classes.get(obfOwner);

            // Enum values don't use the CSV and don't start with field_
            if (srgName.startsWith("field_")) {
                if (srgFieldOwners.containsKey(srgName))
                    System.out.println("SRG field " + srgName + " appears in multiple classes (at least " + srgFieldOwners.get(srgName) + " and " + srgOwner + ")");

                Set<String> owners = srgFieldOwners.computeIfAbsent(srgName, k -> new HashSet<>());
                owners.add(srgOwner);
            }

            forwardSRG.setField(obfOwner, obfName, srgName);
            reverseSRG.setField(srgOwner, srgName, obfName);
        }

        for (Map.Entry<String, String> entry : srg.methods.entrySet()) {
            String obfOwnerNameAndDesc = entry.getKey();
            String srgName = entry.getValue();

            String obfOwnerAndName = obfOwnerNameAndDesc.substring(0, obfOwnerNameAndDesc.indexOf('('));
            String obfOwner = obfOwnerAndName.substring(0, obfOwnerAndName.lastIndexOf('/'));
            String obfName = obfOwnerAndName.substring(obfOwnerAndName.lastIndexOf('/') + 1);
            String obfDesc = obfOwnerNameAndDesc.substring(obfOwnerNameAndDesc.indexOf('('));

            String srgDesc = forwardSRG.mapMethodDescriptor(obfDesc);
            String srgOwner = srg.classes.get(obfOwner);

            Set<String> srgMethodOwnersThis = srgMethodOwnersAndDescs.computeIfAbsent(srgName, k -> new HashSet<>());
            srgMethodOwnersThis.add(srgOwner + srgDesc);

            forwardSRG.setMethod(obfOwner, obfName, obfDesc, srgName);
            reverseSRG.setMethod(srgOwner, srgName, srgDesc, obfName);

            String[] srgExceptions = excFileData.getExceptionClasses(srgOwner, srgName, srgDesc);
            if (srgExceptions.length > 0) {
                List<String> obfExceptions = new ArrayList<>();
                for (String s : srgExceptions)
                    obfExceptions.add(reverseSRG.getClass(s));
                forwardSRG.setExceptions(obfOwner, obfName, obfDesc, obfExceptions);
            }
        }
    }

    private void loadCSVMapping(Map<String, String> fieldNames, Map<String, String> methodNames) {
        for (Map.Entry<String, String> entry : fieldNames.entrySet()) {
            String srgName = entry.getKey();
            String mcpName = entry.getValue();

            if (srgFieldOwners.get(srgName) == null)
                System.out.println("Field exists in CSV but not in SRG: " + srgName + " (CSV name: " + mcpName + ")");
            else {
                for (String srgOwner : srgFieldOwners.get(srgName)) {

                    forwardCSV.setField(srgOwner, srgName, mcpName);
                    reverseCSV.setField(srgOwner, mcpName, srgName);
                }
            }
        }

        for (Map.Entry<String, String> entry : methodNames.entrySet()) {
            String srgName = entry.getKey();
            String mcpName = entry.getValue();

            if (srgMethodOwnersAndDescs.get(srgName) == null) {
                System.out.println("Method exists in CSV but not in SRG: " + srgName + " (CSV name: " + mcpName + ")");
            } else {
                for (String srgOwnerAndDesc : srgMethodOwnersAndDescs.get(srgName)) {
                    String srgDesc = srgOwnerAndDesc.substring(srgOwnerAndDesc.indexOf('('));
                    String srgOwner = srgOwnerAndDesc.substring(0, srgOwnerAndDesc.indexOf('('));

                    forwardCSV.setMethod(srgOwner, srgName, srgDesc, mcpName);
                    reverseCSV.setMethod(srgOwner, mcpName, srgDesc, srgName);
                }
            }
        }
    }


    public Mapping getReverseSRG() {
        return reverseSRG;
    }

    public Mapping getReverseCSV() {
        return reverseCSV;
    }

    public Mapping getForwardSRG() {
        return forwardSRG;
    }

    public Mapping getForwardCSV() {
        return forwardCSV;
    }


    public static String getMCVer(File mcpDir) throws IOException {
        String settingToFind = "ClientVersion";
        File fileToSearchIn = new File(mcpDir, "conf/version.cfg");
        if (!fileToSearchIn.exists()) {
            settingToFind = "MD5Client";
            fileToSearchIn = new File(mcpDir, "conf/mcp.cfg");
        }

        try (Scanner in = new Scanner(fileToSearchIn)) {
            while (in.hasNextLine()) {
                String line = in.nextLine();
                if (line.startsWith(settingToFind))
                    return line.split("=")[1].trim();
            }
            return "unknown";
        }
    }
}
