package com.collectorstools.importer;

import com.collectorstools.exception.TechnicalException;
import com.collectorstools.util.PeselValidator;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Czyta arkusz XLSX i sprowadza wiersze do postaci {@link ImportRow}.
 *
 * <p>Odczyt jest oddzielony od zapisu do bazy: użytkownik ma zobaczyć wynik odczytu
 * wraz z zastrzeżeniami, zanim cokolwiek zostanie zapisane.
 */
public class XlsxReader {

    private final CaseCommentParser commentParser = new CaseCommentParser();

    /**
     * Odczytuje nagłówki z pierwszego wiersza arkusza.
     */
    public List<String> readHeaders(File file) {
        try (Workbook workbook = WorkbookFactory.create(file)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                throw new TechnicalException(
                        "Plik nie zawiera wiersza nagłówka.", "pusty arkusz", null);
            }
            List<String> headers = new ArrayList<>();
            for (int column = 0; column < header.getLastCellNum(); column++) {
                headers.add(text(header.getCell(column)));
            }
            return headers;
        } catch (IOException e) {
            throw readFailure(file, e);
        }
    }

    /**
     * Odczytuje wiersze danych.
     *
     * @param referenceDate data odniesienia dla terminów zapisanych bez roku;
     *                      dla eksportu wierzyciela jest to data jego sporządzenia
     */
    public List<ImportRow> readRows(File file, HeaderMapping mapping, LocalDate referenceDate) {
        try (Workbook workbook = WorkbookFactory.create(file)) {
            Sheet sheet = workbook.getSheetAt(0);
            int headerRow = sheet.getFirstRowNum();

            List<ImportRow> rows = new ArrayList<>();
            for (int index = headerRow + 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (isBlank(row, mapping)) {
                    continue;
                }
                rows.add(readRow(row, mapping, index + 1, referenceDate));
            }
            return rows;
        } catch (IOException e) {
            throw readFailure(file, e);
        }
    }

    private TechnicalException readFailure(File file, IOException cause) {
        return new TechnicalException(
                "Nie udało się odczytać pliku \"" + file.getName() + "\".\n\n"
                        + "Sprawdź, czy plik nie jest otwarty w Excelu i czy ma format XLSX lub XLS.",
                cause.getMessage(), cause);
    }

    private ImportRow readRow(Row row, HeaderMapping mapping, int sourceRowNumber, LocalDate referenceDate) {
        ImportRow importRow = new ImportRow();
        importRow.setSourceRowNumber(sourceRowNumber);

        splitName(importRow, value(row, mapping, ImportField.DEBTOR_NAME));
        readPesel(importRow, value(row, mapping, ImportField.PESEL));

        importRow.setStreet(value(row, mapping, ImportField.STREET));
        importRow.setHouseNumber(value(row, mapping, ImportField.HOUSE_NUMBER));
        importRow.setFlatNumber(value(row, mapping, ImportField.FLAT_NUMBER));
        importRow.setPostalCode(value(row, mapping, ImportField.POSTAL_CODE));
        importRow.setCity(value(row, mapping, ImportField.CITY));
        importRow.setSecondaryAddress(value(row, mapping, ImportField.SECONDARY_ADDRESS));
        importRow.setPhones(splitPhones(value(row, mapping, ImportField.PHONES)));

        importRow.setCaseNumber(value(row, mapping, ImportField.CASE_NUMBER));
        importRow.setCreditor(value(row, mapping, ImportField.CREDITOR));
        importRow.setOriginalCreditor(value(row, mapping, ImportField.ORIGINAL_CREDITOR));
        importRow.setPortfolio(value(row, mapping, ImportField.PORTFOLIO));
        importRow.setProduct(value(row, mapping, ImportField.PRODUCT));
        importRow.setBankAccount(value(row, mapping, ImportField.BANK_ACCOUNT));
        importRow.setCourt(value(row, mapping, ImportField.COURT));
        importRow.setCourtSignature(value(row, mapping, ImportField.COURT_SIGNATURE));

        importRow.setBalance(amount(row, mapping, ImportField.BALANCE));
        importRow.setPrincipal(amount(row, mapping, ImportField.PRINCIPAL));
        importRow.setInterest(amount(row, mapping, ImportField.INTEREST));
        importRow.setCosts(amount(row, mapping, ImportField.COSTS));

        importRow.setServiceStartDate(date(row, mapping, ImportField.SERVICE_START_DATE));

        importRow.setFieldVisit(isYes(value(row, mapping, ImportField.FIELD_VISIT)));
        importRow.setVisitNote(value(row, mapping, ImportField.VISIT_NOTE));

        importRow.setComment(commentParser.parse(value(row, mapping, ImportField.COMMENT), referenceDate));
        importRow.getComment().warnings().forEach(importRow::warn);

        validate(importRow);
        return importRow;
    }

    /**
     * Eksport podaje imię i nazwisko w jednym polu, w kolejności „IMIĘ NAZWISKO".
     * Pierwszy człon traktujemy jako imię, resztę jako nazwisko — dzięki temu
     * nazwiska dwuczłonowe nie są ucinane.
     */
    private void splitName(ImportRow importRow, String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return;
        }
        String[] parts = fullName.strip().split("\\s+", 2);
        if (parts.length == 1) {
            importRow.setLastName(parts[0]);
            importRow.warn("Nazwa dłużnika zawiera tylko jeden człon — zapisano jako nazwisko.");
            return;
        }
        importRow.setFirstName(parts[0]);
        importRow.setLastName(parts[1]);
    }

    private void readPesel(ImportRow importRow, String raw) {
        if (raw == null || raw.isBlank()) {
            importRow.warn("Brak numeru PESEL — dopasowanie do istniejącego dłużnika nie będzie możliwe.");
            return;
        }
        String pesel = raw.strip();
        if (!PeselValidator.isValid(pesel)) {
            importRow.reject("Nieprawidłowy PESEL: " + pesel);
            return;
        }
        importRow.setPesel(pesel);
    }

    private void validate(ImportRow row) {
        if (row.getLastName() == null || row.getLastName().isBlank()) {
            row.reject("Brak nazwy dłużnika.");
        }
        if (row.getCaseNumber() == null || row.getCaseNumber().isBlank()) {
            row.reject("Brak numeru sprawy.");
        }
        if (row.getCreditor() == null || row.getCreditor().isBlank()) {
            row.reject("Brak wierzyciela.");
        }
        if (row.getBalance() == null) {
            row.reject("Brak salda sprawy.");
        } else if (row.getBalance().signum() < 0) {
            row.reject("Saldo sprawy jest ujemne: " + row.getBalance().toPlainString());
        }

        validateBreakdown(row);
    }

    /**
     * Saldo musi odpowiadać sumie kapitału, odsetek i kosztów (reguła BR-12).
     * Rozjazd oznacza błąd po stronie eksportu — wiersz trafia do odrzuconych,
     * bo zapisanie go wprowadziłoby do bazy dane wewnętrznie sprzeczne.
     */
    private void validateBreakdown(ImportRow row) {
        if (row.getBalance() == null) {
            return;
        }
        BigDecimal sum = zeroIfNull(row.getPrincipal())
                .add(zeroIfNull(row.getInterest()))
                .add(zeroIfNull(row.getCosts()));
        if (sum.signum() == 0) {
            return;
        }
        if (sum.subtract(row.getBalance()).abs().compareTo(new BigDecimal("0.01")) > 0) {
            row.reject("Rozbicie salda nie zgadza się z kwotą: " + sum.toPlainString()
                    + " zamiast " + row.getBalance().toPlainString());
        }
    }

    private List<String> splitPhones(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.stream(raw.split("[,;]"))
                .map(String::strip)
                .filter(phone -> !phone.isEmpty())
                .distinct()
                .toList());
    }

    private static boolean isYes(String value) {
        return value != null && value.strip().equalsIgnoreCase("tak");
    }

    private boolean isBlank(Row row, HeaderMapping mapping) {
        if (row == null) {
            return true;
        }
        for (ImportField field : ImportField.values()) {
            if (mapping.isMapped(field) && !text(row.getCell(mapping.columnOf(field))).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String value(Row row, HeaderMapping mapping, ImportField field) {
        if (!mapping.isMapped(field)) {
            return null;
        }
        String value = text(row.getCell(mapping.columnOf(field)));
        return value.isEmpty() ? null : value;
    }

    private BigDecimal amount(Row row, HeaderMapping mapping, ImportField field) {
        if (!mapping.isMapped(field)) {
            return null;
        }
        Cell cell = row.getCell(mapping.columnOf(field));
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        String raw = text(cell);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw.replace(" ", "").replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Daty w eksporcie bywają tekstem (np. {@code 2016-11-15 00:00:00.000}), a nie
     * wartością daty — pierwsze dziesięć znaków wystarcza do odczytu.
     */
    private LocalDate date(Row row, HeaderMapping mapping, ImportField field) {
        if (!mapping.isMapped(field)) {
            return null;
        }
        Cell cell = row.getCell(mapping.columnOf(field));
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String raw = text(cell);
        if (raw.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().strip();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double number = cell.getNumericCellValue();
                yield number == Math.floor(number)
                        ? String.valueOf((long) number)
                        : String.valueOf(number);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
