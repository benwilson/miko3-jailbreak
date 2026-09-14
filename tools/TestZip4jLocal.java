import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import java.io.File;
import java.util.List;

public class TestZip4jLocal {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -cp zip4j-*.jar TestZip4jLocal <zipfile>");
            System.exit(1);
        }
        
        String zipPath = args[0];
        File zipFile = new File(zipPath);
        
        System.out.println("Testing: " + zipPath);
        System.out.println("Exists: " + zipFile.exists());
        
        try {
            ZipFile zf = new ZipFile(zipFile);
            
            System.out.println("isValidZipFile: " + zf.isValidZipFile());
            
            List<FileHeader> headers = zf.getFileHeaders();
            System.out.println("File count: " + headers.size());
            
            for (int i = 0; i < headers.size(); i++) {
                FileHeader fh = headers.get(i);
                System.out.println("  [" + i + "] " + fh.getFileName() 
                    + " dir=" + fh.isDirectory()
                    + " size=" + fh.getFileName());
            }
            
            // Try extraction
            File dest = new File(zipPath + "_extracted");
            dest.mkdirs();
            
            System.out.println("\nExtracting to: " + dest.getAbsolutePath());
            zf.extractAll(dest.getAbsolutePath());
            
            System.out.println("Extraction complete. Contents:");
            for (File f : dest.listFiles()) {
                System.out.println("  " + f.getName());
            }
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}