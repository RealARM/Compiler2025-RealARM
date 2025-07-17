import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

import Frontend.SysYLexer;
import Frontend.TokenStream;

public class TestLexer {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java TestLexer <input_file> <output_file>");
            System.exit(1);
        }

        String inputFile = args[0];
        String outputFile = args[1];

        try {
            // 直接使用文件路径构造词法分析器
            SysYLexer lexer = new SysYLexer(inputFile);
            TokenStream tokenStream = lexer.tokenize();
            
            // Write the result to the output file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
                // TokenStream's toString method already formats the output as required
                writer.write(tokenStream.toString());
            }
            
            System.out.println("Lexical analysis completed successfully.");
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
} 