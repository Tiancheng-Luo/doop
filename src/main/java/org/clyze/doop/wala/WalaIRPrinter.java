package org.clyze.doop.wala;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.ShrikeClass;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SymbolTable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.Collection;

public class WalaIRPrinter {

    private WalaRepresentation _rep;
    private IAnalysisCacheView _cache;
    private String _outputDir;

    public WalaIRPrinter(IAnalysisCacheView cache)
    {
        _rep = WalaRepresentation.getRepresentation();
        _cache = cache;
    }

    public void printIR(IClass cl)
    {
//        PrintWriter writerOut = new PrintWriter(new EscapedWriter(new OutputStreamWriter((OutputStream)streamOut)));
        ShrikeClass shrikeClass = (ShrikeClass) cl;
        String fileName = "WalaFacts/IR/" + cl.getReference().getName().toString().replaceAll("/",".").replaceFirst("L","");
        File file = new File(fileName);
        file.getParentFile().getParentFile().mkdirs();
        file.getParentFile().mkdirs();

        Collection<IField> fields = cl.getAllFields();
        Collection<IMethod> methods = cl.getDeclaredMethods();
        Collection<IClass> interfaces =  cl.getAllImplementedInterfaces();
        try {
            PrintWriter printWriter = new PrintWriter(file);
            if(shrikeClass.isPublic())
                printWriter.write("public ");
            else if(shrikeClass.isPrivate())
                printWriter.write("private ");

            if(shrikeClass.isAbstract())
                printWriter.write("abstract ");

            if(shrikeClass.isInterface())
                printWriter.write("interface ");
            else
                printWriter.write("class ");

            printWriter.write(cl.getReference().getName().toString().replaceAll("/",".").replaceFirst("L",""));

            printWriter.write("\n{\n");
            for(IField field : fields )
            {
                printWriter.write("\t");
                if(field.isPublic())
                    printWriter.write("public ");
                else if(field.isPrivate())
                    printWriter.write("private ");
                else if(field.isProtected())
                    printWriter.write("protected ");
                if(field.isStatic())
                    printWriter.write("static ");
                printWriter.write(_rep.fixTypeString(field.getFieldTypeReference().toString()) + " " + field.getName() + ";\n");
                //printWriter.write("\t" + field.getFieldTypeReference().toString() + " " + field.getReference().getSignature() + "\n");
                //printWriter.write("\t" + field.getFieldTypeReference().toString() + " " + field.getReference().toString() + "\n");
            }
            for (IMethod m : methods)
            {
                printWriter.write("\n\t");
                if(m.isPublic())
                    printWriter.write("public ");
                else if(m.isPrivate())
                    printWriter.write("private ");
                else if(m.isProtected())
                    printWriter.write("protected ");

                if(m.isStatic())
                    printWriter.write("static ");

                if(m.isFinal())
                    printWriter.write("final ");

                if(m.isAbstract())
                    printWriter.write("abstract ");

                if(m.isSynchronized())
                    printWriter.write("synchronized ");

                if(m.isNative())
                    printWriter.write("native ");

                printWriter.write(_rep.fixTypeString(m.getReturnType().toString()) + " " + m.getReference().getName().toString() + "(");
                for (int i = 0; i < m.getNumberOfParameters(); i++) {
                    printWriter.write(_rep.fixTypeString(m.getParameterType(i).toString()) + " ");
                    if (i < m.getNumberOfParameters() - 1)
                        printWriter.write(", ");
                }
                printWriter.write(")\n\t{\n");
                if(!(m.isAbstract() || m.isNative()))
                {
                    printIR(m,printWriter);
                }
                printWriter.write("\t}\n");

            }

            printWriter.write("}\n");
            printWriter.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    public void printIR(IMethod m, PrintWriter writer)
    {
        IR ir = _cache.getIR(m, Everywhere.EVERYWHERE);
        SSAInstruction[] instructions = ir.getInstructions();
        SSACFG cfg = ir.getControlFlowGraph();
        SymbolTable symbolTable = ir.getSymbolTable();
        for (int i = 0; i <=cfg.getMaxNumber(); i++) {
            writer.write("\t\tBB "+ i + "\n");
            SSACFG.BasicBlock basicBlock = cfg.getNode(i);
            int start = basicBlock.getFirstInstructionIndex();
            int end = basicBlock.getLastInstructionIndex();

            for (int j = start; j <= end; j++) {
                if (instructions[j] != null) {
                    writer.write("\t\t\t" + instructions[j].toString(symbolTable) + "\n");

                }
            }
        }
    }
}