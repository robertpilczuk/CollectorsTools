package declarations;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Scanner;

public class DeclarationManagement {

    public void createDeclaration(LocalDate localDate, Declaration declaration, Map<LocalDate, Declaration> db) {
        //dodaję deklaracje
        db.put(localDate,declaration);
        //wyświtlam deklaracje
        db.forEach((num, val)-> System.out.println(String.format("%s: %s",num,val)));
                        //dodaję do pliku
        //sprawdzam czy plik istnieje, jeśli nie to tworzę go

        //================================================================
        //Pytanie: czy istnieje funkcjonalnośc pozwalająca zrobić coś jeden raz na pczątku działania programu
        //tak, żeby później nie robić tego w kółko?

        String path = addingDeclarationToFile();
        IO io = new IO();
        if (!io.checkIfFileExists(path)){
            io.creatingFile(path);
        }
        try{
            FileWriter fileWriter = new FileWriter(path,true);
            BufferedWriter writer = new BufferedWriter(fileWriter);

            //pobieram wartości z mapy i dodaję je do pliku
            for (Map.Entry<LocalDate, Declaration> map :db.entrySet()) {
                writer.write(map.getValue().toString());
                writer.write("\r\n");
                writer.flush();
            }
            System.out.println("Zapis udany");
            writer.close();
            } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    public String addingDeclarationToFile() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Dodać do pliku? (y/n) dodaj używając domyślnego pliku (u) - C:\\collectorsTools\\myDeclarations.csv");
        String y_n = scanner.next();
        if (y_n.equals("y")) {
            System.out.println("Podaj ścieżke pliku:");
            return scanner.next();
        }
        if (y_n.equals("u")) {
            System.out.println("Użyto domyślnej ścieżki pliku: D:\\collectorsTools\\myDeclarations.csv");
            return "D:\\collectorsTools\\myDeclarations.csv";
        }
        else {
            return "Twoje deklaracje znajdują się w programie";
        }

    }

    public void retrieveByDate() {

    }

    public void getDeclarationsSum(){

    }

}
