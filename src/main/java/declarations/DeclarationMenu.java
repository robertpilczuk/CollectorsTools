package declarations;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

public class DeclarationMenu {
    private final Scanner sc = new Scanner(System.in);
    private static Map<Integer,String> COMMANDS = new LinkedHashMap<>();
    private static DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

}
