package com.collectorstools.service;

import com.collectorstools.db.TransactionManager;
import com.collectorstools.model.AuditEntity;
import com.collectorstools.model.AuditOperation;
import com.collectorstools.model.Debt;
import com.collectorstools.model.DebtStatus;
import com.collectorstools.model.Declaration;
import com.collectorstools.model.DeclarationStatus;
import com.collectorstools.model.Payment;
import com.collectorstools.repository.DebtRepository;
import com.collectorstools.repository.DeclarationRepository;
import com.collectorstools.repository.PaymentRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Rejestracja wpłat wraz z aktualizacją salda sprawy i stanu deklaracji.
 *
 * <p>Zapis wpłaty, zmniejszenie salda i przeliczenie statusu deklaracji wykonują się
 * w jednej transakcji. Bez tego przerwanie w połowie zostawiłoby wpłatę bez
 * zaktualizowanego salda albo saldo obniżone bez śladu, skąd wzięła się zmiana.
 */
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final DebtRepository debtRepository;
    private final DeclarationRepository declarationRepository;
    private final TransactionManager transactionManager;
    private final AuditService auditService;

    public PaymentService() {
        this.paymentRepository = new PaymentRepository();
        this.debtRepository = new DebtRepository();
        this.declarationRepository = new DeclarationRepository();
        this.transactionManager = new TransactionManager();
        this.auditService = new AuditService();
    }

    /**
     * Konstruktor bez audytu — używany przez testy, które go nie weryfikują.
     */
    public PaymentService(PaymentRepository paymentRepository,
                          DebtRepository debtRepository,
                          DeclarationRepository declarationRepository,
                          TransactionManager transactionManager) {
        this(paymentRepository, debtRepository, declarationRepository,
                transactionManager, AuditService.noOp());
    }

    public PaymentService(PaymentRepository paymentRepository,
                          DebtRepository debtRepository,
                          DeclarationRepository declarationRepository,
                          TransactionManager transactionManager,
                          AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.debtRepository = debtRepository;
        this.declarationRepository = declarationRepository;
        this.transactionManager = transactionManager;
        this.auditService = auditService;
    }

    /**
     * Rejestruje wpłatę: zapisuje ją, obniża saldo sprawy i przelicza stan deklaracji.
     *
     * <p>Wszystko w jednej transakcji — błąd na dowolnym kroku cofa całość.
     */
    public Payment registerPayment(Payment payment) {
        validate(payment);

        return transactionManager.executeWithResult(() -> {
            Debt debt = debtRepository.findById(payment.getDebtId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Nie znaleziono sprawy, do której miała trafić wpłata."));

            BigDecimal remaining = orZero(debt.getRemainingAmount());
            if (payment.getAmount().compareTo(remaining) > 0) {
                throw new IllegalArgumentException(String.format(
                        "Kwota wpłaty (%s zł) przekracza pozostałe saldo sprawy (%s zł). "
                                + "Maksymalna możliwa wpłata to %s zł.",
                        money(payment.getAmount()), money(remaining), money(remaining)));
            }

            Declaration declaration = loadDeclarationIfLinked(payment, debt);

            allocate(payment, debt);
            payment.setCreatedAt(LocalDate.now());
            payment.setUserId(CurrentUser.id());
            if (payment.getSource() == null) {
                payment.setSource(Payment.SOURCE_MANUAL);
            }
            paymentRepository.save(payment);

            debt.setRemainingAmount(remaining.subtract(payment.getAmount()));
            closeIfFullyRepaid(debt, payment.getPaymentDate());
            debtRepository.update(debt);

            if (declaration != null) {
                refreshDeclarationStatus(declaration);
            }

            auditService.record(AuditEntity.PAYMENT, payment.getId(), debt.getDebtorId(),
                    AuditOperation.PAYMENT_REGISTERED, "Saldo sprawy",
                    money(remaining), money(debt.getRemainingAmount()), payment.getNote());
            return payment;
        });
    }

    /**
     * Wycofuje błędnie wprowadzoną wpłatę, przywracając saldo sprawy dokładnie
     * do stanu sprzed niej — dzięki zapisanemu podziałowi na kapitał, odsetki i koszty
     * nie trzeba go odtwarzać metodą przybliżoną.
     */
    public void reversePayment(long paymentId) {
        transactionManager.execute(() -> {
            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new IllegalArgumentException("Nie znaleziono wpłaty do wycofania."));

            Debt debt = debtRepository.findById(payment.getDebtId())
                    .orElseThrow(() -> new IllegalArgumentException("Nie znaleziono sprawy powiązanej z wpłatą."));

            debt.setRemainingAmount(orZero(debt.getRemainingAmount()).add(payment.getAmount()));
            debt.setCostAmount(orZero(debt.getCostAmount()).add(orZero(payment.getAllocatedCost())));
            debt.setInterestAmount(orZero(debt.getInterestAmount()).add(orZero(payment.getAllocatedInterest())));
            debt.setPrincipalAmount(orZero(debt.getPrincipalAmount()).add(orZero(payment.getAllocatedPrincipal())));
            reopenIfNoLongerRepaid(debt);
            debtRepository.update(debt);

            Long declarationId = payment.getDeclarationId();
            paymentRepository.delete(paymentId);

            auditService.record(AuditEntity.PAYMENT, paymentId, debt.getDebtorId(),
                    AuditOperation.PAYMENT_REVERSED, "Wycofana wpłata",
                    money(payment.getAmount()) + " zł z " + payment.getPaymentDate(), null,
                    payment.getNote());

            if (declarationId != null) {
                Optional<Declaration> declaration = declarationRepository.findById(declarationId);
                if (declaration.isPresent()) {
                    refreshDeclarationStatus(declaration.get());
                }
            }
        });
    }

    public List<Payment> findByDebtId(long debtId) {
        try {
            return paymentRepository.findByDebtId(debtId);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd pobierania wpłat sprawy: " + e.getMessage(), e);
        }
    }

    public List<Payment> findByDebtorId(long debtorId) {
        try {
            return paymentRepository.findByDebtorId(debtorId);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd pobierania wpłat dłużnika: " + e.getMessage(), e);
        }
    }

    public BigDecimal sumByDebtorId(long debtorId) {
        try {
            return paymentRepository.sumByDebtorId(debtorId);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd sumowania wpłat: " + e.getMessage(), e);
        }
    }

    public BigDecimal sumByDeclarationId(long declarationId) {
        try {
            return paymentRepository.sumByDeclarationId(declarationId);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd sumowania wpłat deklaracji: " + e.getMessage(), e);
        }
    }

    public int countByDebtId(long debtId) {
        try {
            return paymentRepository.countByDebtId(debtId);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd liczenia wpłat: " + e.getMessage(), e);
        }
    }

    public BigDecimal sumAll() {
        try {
            return paymentRepository.sumAll();
        } catch (SQLException e) {
            throw new RuntimeException("Błąd sumowania wpłat: " + e.getMessage(), e);
        }
    }

    // ===== logika wewnętrzna =====

    private Declaration loadDeclarationIfLinked(Payment payment, Debt debt) throws SQLException {
        if (payment.getDeclarationId() == null) {
            return null;
        }
        Declaration declaration = declarationRepository.findById(payment.getDeclarationId())
                .orElseThrow(() -> new IllegalArgumentException("Nie znaleziono wskazanej deklaracji."));

        if (declaration.getStatus() == DeclarationStatus.CANCELLED) {
            throw new IllegalArgumentException(
                    "Deklaracja została anulowana — nie można zarejestrować do niej wpłaty.");
        }
        if (!declaration.getDebtorId().equals(debt.getDebtorId())) {
            throw new IllegalArgumentException(
                    "Deklaracja należy do innego dłużnika niż wskazana sprawa.");
        }
        return declaration;
    }

    /**
     * Dzieli wpłatę między składniki salda w kolejności: koszty, odsetki, kapitał.
     *
     * <p>Kolejność wynika z art. 451 § 1 Kodeksu cywilnego — wierzyciel zalicza wpłatę
     * najpierw na należności uboczne. Podział zapisujemy przy wpłacie, żeby dało się
     * ją później wycofać bez utraty informacji.
     *
     * <p>Sprawy bez znanego rozbicia salda (wszystkie składniki zerowe) obsługujemy
     * bez podziału — zmniejszamy wyłącznie saldo łączne.
     */
    private void allocate(Payment payment, Debt debt) {
        BigDecimal remainingToAllocate = payment.getAmount();

        BigDecimal costs = orZero(debt.getCostAmount());
        BigDecimal interest = orZero(debt.getInterestAmount());
        BigDecimal principal = orZero(debt.getPrincipalAmount());

        if (costs.add(interest).add(principal).compareTo(BigDecimal.ZERO) == 0) {
            payment.setAllocatedCost(BigDecimal.ZERO);
            payment.setAllocatedInterest(BigDecimal.ZERO);
            payment.setAllocatedPrincipal(BigDecimal.ZERO);
            return;
        }

        BigDecimal toCosts = remainingToAllocate.min(costs);
        remainingToAllocate = remainingToAllocate.subtract(toCosts);

        BigDecimal toInterest = remainingToAllocate.min(interest);
        remainingToAllocate = remainingToAllocate.subtract(toInterest);

        BigDecimal toPrincipal = remainingToAllocate.min(principal);

        payment.setAllocatedCost(toCosts);
        payment.setAllocatedInterest(toInterest);
        payment.setAllocatedPrincipal(toPrincipal);

        debt.setCostAmount(costs.subtract(toCosts));
        debt.setInterestAmount(interest.subtract(toInterest));
        debt.setPrincipalAmount(principal.subtract(toPrincipal));
    }

    /**
     * Ustala stan deklaracji na podstawie faktycznych wpłat do niej przypisanych.
     *
     * <p>Stan i data wynikają wyłącznie z zawartości tabeli wpłat, nigdy z parametru —
     * dzięki temu ta sama metoda obsługuje rejestrację wpłaty i jej wycofanie.
     *
     * <p>Deklaracja złamana może wrócić do stanu dotrzymanej: wpłata po terminie
     * jest w windykacji zjawiskiem codziennym, a powód złamania zostaje zachowany.
     */
    private void refreshDeclarationStatus(Declaration declaration) throws SQLException {
        DeclarationStatus previousStatus = declaration.getStatus();
        List<Payment> payments = paymentRepository.findByDeclarationId(declaration.getId());

        BigDecimal paid = payments.stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate lastPaymentDate = payments.isEmpty()
                ? null : payments.get(payments.size() - 1).getPaymentDate();

        if (payments.isEmpty()) {
            // Wszystkie wpłaty wycofane — deklaracja wraca do stanu sprzed nich
            declaration.setStatus(isOverdue(declaration)
                    ? DeclarationStatus.BROKEN : DeclarationStatus.PENDING);
            declaration.setPaymentDate(null);
        } else if (paid.compareTo(declaration.getPromisedAmount()) >= 0) {
            declaration.setStatus(DeclarationStatus.FULFILLED);
            declaration.setPaymentDate(lastPaymentDate);
        } else {
            declaration.setStatus(DeclarationStatus.PARTIALLY_FULFILLED);
            declaration.setPaymentDate(lastPaymentDate);
        }
        declarationRepository.update(declaration);

        // Stan deklaracji zmienia się tu z pominięciem DeclarationService, więc wpis
        // audytowy musi powstać w tym miejscu — inaczej przejście "Oczekująca →
        // Dotrzymana" wywołane wpłatą nie zostawiłoby żadnego śladu.
        auditService.recordChange(AuditEntity.DECLARATION, declaration.getId(),
                declaration.getDebtorId(), AuditOperation.DECLARATION_STATUS, "Stan deklaracji",
                previousStatus != null ? previousStatus.getDisplayName() : null,
                declaration.getStatus().getDisplayName(), null);
    }

    /**
     * Spłata całości salda zamyka sprawę automatycznie.
     *
     * <p>Zerowe saldo nie pozostawia miejsca na decyzję windykatora — sprawa jest
     * spłacona i nie powinna dłużej figurować na liście zadań ani w sumie długów.
     * Zamykamy tylko sprawy aktywne; stanu ustawionego ręcznie (umorzenie, droga
     * sądowa) wpłata nie nadpisuje.
     */
    private void closeIfFullyRepaid(Debt debt, LocalDate paymentDate) {
        if (debt.getRemainingAmount().compareTo(BigDecimal.ZERO) != 0) {
            return;
        }
        if (debt.getStatus() == null || debt.getStatus() == DebtStatus.ACTIVE) {
            debt.setStatus(DebtStatus.CLOSED_PAID);
            debt.setClosedAt(paymentDate);
        }
    }

    /**
     * Wycofanie wpłaty przywraca saldo, więc sprawa zamknięta jako spłacona
     * wraca do obsługi. Zamknięć z innych przyczyn operacja nie rusza.
     */
    private void reopenIfNoLongerRepaid(Debt debt) {
        if (debt.getStatus() == DebtStatus.CLOSED_PAID
                && debt.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0) {
            debt.setStatus(DebtStatus.ACTIVE);
            debt.setClosedAt(null);
            debt.setCloseReason(null);
        }
    }

    private boolean isOverdue(Declaration declaration) {
        return declaration.getPromisedDate() != null
                && LocalDate.now().isAfter(declaration.getPromisedDate());
    }

    private void validate(Payment payment) {
        if (payment.getDebtId() == null)
            throw new IllegalArgumentException("Wpłata musi być przypisana do sprawy.");
        if (payment.getPaymentDate() == null)
            throw new IllegalArgumentException("Data wpłaty jest wymagana.");
        if (payment.getAmount() == null || payment.getAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Kwota wpłaty musi być większa od zera.");
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Kwota w zapisie, jakiego oczekuje polski użytkownik: dwa miejsca po przecinku
     * i przecinek dziesiętny. SQLite przechowuje kwoty jako REAL, więc bez tego
     * komunikat pokazywałby "1000.0 zł" zamiast "1000,00 zł".
     */
    private static String money(BigDecimal value) {
        return orZero(value).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
