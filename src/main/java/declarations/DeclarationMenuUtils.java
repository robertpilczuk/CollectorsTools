package declarations;

import java.util.LinkedHashMap;
import java.util.Map;

public class DeclarationMenuUtils {
    static Map<Integer,String> COMMANDS = new LinkedHashMap<>();
    static Map<Integer,String> DECLARATIONS_MENU = new LinkedHashMap<>();

    static {
        COMMANDS.put(1, "Stwórz deklarację");
        COMMANDS.put(2, "Sprawdź najbliższe (od dziś do podanej liczby dni)/zakres dat");
        COMMANDS.put(3, "Wszystkie deklaracje");
        COMMANDS.put(4, "Edycja deklaracji (edytuj/usuń)");
        COMMANDS.put(5, "Suma deklaracji (od dzisiaj do...)/(zakres dat)/(wszystkie)");
        COMMANDS.put(6, "Plik deklaracji (podgląd)/(edycja)");
        COMMANDS.put(9, "Wyjście");
        DECLARATIONS_MENU.put(1, "Sprawdź od dzisiaj do danej liczby dni w przód");
        DECLARATIONS_MENU.put(2, "Sprawdź z danego zakresu dat");
        DECLARATIONS_MENU.put(3, "Sprawdź wszystkie deklaracje");
    }
    static void showMenu(){
        System.out.println("Witaj w CollectorsTools");
        COMMANDS.forEach((cmd,val)-> System.out.println(String.format("%s: %s",cmd,val)));
    }
    static void showDeclarationsHandlerMenu(){
        DECLARATIONS_MENU.forEach((cmd,val)-> System.out.println(String.format("%s: %s",cmd,val)));
    }

}
