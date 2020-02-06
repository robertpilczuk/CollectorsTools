package declarations;

import java.io.File;
import java.io.IOException;

public class IO {
    void creatingFile(String path){
        File file = new File(path);
        try{
            if (file.createNewFile()){
                System.out.println("Stworzono plik");
            }else{
                System.out.println("Plik już istnieje");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    boolean checkIfFileExists(String path){
        File file = new File(path);
        if (file.exists()){
            System.out.println("Plik istnieje");
            return true;
        }else{
            System.out.println("Plik nie istnieje");
            return false;
        }

    }
}
