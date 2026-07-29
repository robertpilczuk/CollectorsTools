package com.collectorstools.service;

import com.collectorstools.model.Debt;
import com.collectorstools.model.Debtor;
import com.collectorstools.model.Declaration;
import com.collectorstools.model.Interaction;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Eksport karty dłużnika do PDF (Apache PDFBox).
 * Próbuje osadzić font Unicode z systemu; w razie braku używa Helvetiki,
 * a tekst z polskimi znakami jest wtedy transliterowany do ASCII (bez wyjątku).
 */
public class DebtorPdfExporter {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final float MARGIN = 50;
    private static final float LEADING = 16;

    private static final String[] FONT_PATHS = {
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "C:/Windows/Fonts/arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"
    };

    private final DebtService debtService = new DebtService();
    private final DeclarationService declarationService = new DeclarationService();
    private final InteractionService interactionService = new InteractionService();

    public void export(Debtor debtor, File file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont font = loadUnicodeFont(doc);
            if (font == null) font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            Renderer r = new Renderer(doc, font);
            long id = debtor.getId();

            r.write("Karta dłużnika", 18);
            r.gap();
            r.write(debtor.getFullName(), 14);
            r.write("PESEL: " + nz(debtor.getPesel()) + "    Nr dowodu: " + nz(debtor.getIdNumber()), 11);
            r.write("Telefon: " + nz(debtor.getPhone()) + "    E-mail: " + nz(debtor.getEmail()), 11);
            r.write("Adres: " + nz(debtor.getStreet()) + ", " + nz(debtor.getPostalCode()) + " " + nz(debtor.getCity()), 11);
            if (debtor.getNotes() != null && !debtor.getNotes().isBlank()) {
                r.write("Notatki: " + debtor.getNotes(), 11);
            }
            r.gap();

            r.write("Sprawy / długi", 14);
            List<Debt> debts = debtService.findByDebtorId(id);
            if (debts.isEmpty()) r.write("(brak)", 11);
            for (Debt d : debts) {
                r.write("• " + nz(d.getCaseNumber()) + " — " + nz(d.getCreditor())
                        + " — pozostało " + d.getRemainingAmount() + " / " + d.getTotalAmount() + " PLN", 11);
            }
            r.gap();

            r.write("Deklaracje", 14);
            List<Declaration> decls = declarationService.findByDebtorId(id);
            if (decls.isEmpty()) r.write("(brak)", 11);
            for (Declaration d : decls) {
                String date = d.getPromisedDate() != null ? d.getPromisedDate().format(FMT) : "—";
                r.write("• " + date + " — " + d.getPromisedAmount() + " PLN — " + d.getStatus().getDisplayName(), 11);
            }
            r.gap();

            r.write("Historia kontaktów", 14);
            List<Interaction> inter = interactionService.findByDebtorId(id);
            if (inter.isEmpty()) r.write("(brak)", 11);
            for (Interaction i : inter) {
                String date = i.getInteractionDate() != null ? i.getInteractionDate().format(FMT) : "—";
                r.write("• " + date + " — " + String.valueOf(i.getContactType()) + " — " + nz(i.getNotes()), 11);
            }

            r.close();
            doc.save(file);
        }
    }

    private PDFont loadUnicodeFont(PDDocument doc) {
        for (String path : FONT_PATHS) {
            File f = new File(path);
            if (f.exists()) {
                try {
                    return PDType0Font.load(doc, f);
                } catch (IOException ignored) {
                    // próbuj kolejny
                }
            }
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String toAscii(String text) {
        String t = text == null ? "" : text.replace("ł", "l").replace("Ł", "L");
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.replaceAll("[^\\x20-\\x7E]", "?");
    }

    /**
     * Pisarz z automatycznym łamaniem stron i fallbackiem ASCII gdy font nie ma glifu.
     */
    private static final class Renderer {
        private final PDDocument doc;
        private final PDFont font;
        private PDPageContentStream cs;
        private float y;

        Renderer(PDDocument doc, PDFont font) throws IOException {
            this.doc = doc;
            this.font = font;
            newPage();
        }

        private void newPage() throws IOException {
            if (cs != null) cs.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        void write(String text, float size) throws IOException {
            if (y < MARGIN) newPage();
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(MARGIN, y);
            try {
                cs.showText(text);
            } catch (IllegalArgumentException ex) {
                cs.showText(toAscii(text));
            }
            cs.endText();
            y -= LEADING;
        }

        void gap() {
            y -= 8;
        }

        void close() throws IOException {
            if (cs != null) cs.close();
        }
    }
}
