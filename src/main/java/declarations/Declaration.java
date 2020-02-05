package declarations;

import java.time.LocalDate;

public class Declaration {
    private LocalDate declarationDate;
    private int declarationValue;
    private int declarationCaseNumber;

    public Declaration(LocalDate declarationDate, int declarationValue, int declarationCaseNumber) {
        this.declarationDate = declarationDate;
        this.declarationValue = declarationValue;
        this.declarationCaseNumber = declarationCaseNumber;
    }

    public LocalDate getDeclarationDate() {
        return declarationDate;
    }

    public int getDeclarationValue() {
        return declarationValue;
    }

    public int getDeclarationCaseNumber() {
        return declarationCaseNumber;
    }

    public void setDeclarationDate(LocalDate declarationDate) {
        this.declarationDate = declarationDate;
    }

    public void setDeclarationValue(int declarationValue) {
        this.declarationValue = declarationValue;
    }

    public void setDeclarationCaseNumber(int declarationCaseNumber) {
        this.declarationCaseNumber = declarationCaseNumber;
    }

    @Override
    public String toString() {
        return declarationDate + "\r kwota: " +
                declarationValue + "pln" + "\r nr sprawy: " +
                declarationCaseNumber + "\n";
    }
}
