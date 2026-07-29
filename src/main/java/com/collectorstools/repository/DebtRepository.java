package com.collectorstools.repository;

import com.collectorstools.db.DatabaseManager;
import com.collectorstools.model.Debt;
import com.collectorstools.model.DebtStatus;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DebtRepository {

    private final Connection conn;

    public DebtRepository() {
        this.conn = DatabaseManager.getInstance().getConnection();
    }

    public DebtRepository(Connection conn) {
        this.conn = conn;
    }

    private static final String COLUMNS = """
            debtor_id, case_number, creditor, original_creditor, portfolio, product, bank_account,
            total_amount, remaining_amount, principal_amount, interest_amount, cost_amount,
            case_category, credit_status, settlement_allowed, court, court_signature,
            debt_date, service_start_date, enforcement_start_date, description,
            status, closed_at, close_reason
        """;

    public Debt save(Debt debt) throws SQLException {
        String sql = "INSERT INTO debts (" + COLUMNS + ") VALUES ("
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, debt.getDebtorId());
            bindCommon(ps, debt, 2);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) debt.setId(keys.getLong(1));
            }
        }
        return debt;
    }

    public void update(Debt debt) throws SQLException {
        String sql = """
            UPDATE debts SET case_number=?, creditor=?, original_creditor=?, portfolio=?, product=?,
                             bank_account=?, total_amount=?, remaining_amount=?, principal_amount=?,
                             interest_amount=?, cost_amount=?, case_category=?, credit_status=?,
                             settlement_allowed=?, court=?, court_signature=?, debt_date=?,
                             service_start_date=?, enforcement_start_date=?, description=?,
                             status=?, closed_at=?, close_reason=?
            WHERE id=?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int next = bindCommon(ps, debt, 1);
            ps.setLong(next, debt.getId());
            ps.executeUpdate();
        }
    }

    /**
     * Ustawia wszystkie pola sprawy poza identyfikatorami, począwszy od podanej pozycji.
     *
     * @return numer kolejnej wolnej pozycji
     */
    private int bindCommon(PreparedStatement ps, Debt debt, int from) throws SQLException {
        int i = from;
        ps.setString(i++, debt.getCaseNumber());
        ps.setString(i++, debt.getCreditor());
        ps.setString(i++, debt.getOriginalCreditor());
        ps.setString(i++, debt.getPortfolio());
        ps.setString(i++, debt.getProduct());
        ps.setString(i++, debt.getBankAccount());
        ps.setBigDecimal(i++, debt.getTotalAmount());
        ps.setBigDecimal(i++, debt.getRemainingAmount());
        ps.setBigDecimal(i++, zeroIfNull(debt.getPrincipalAmount()));
        ps.setBigDecimal(i++, zeroIfNull(debt.getInterestAmount()));
        ps.setBigDecimal(i++, zeroIfNull(debt.getCostAmount()));
        ps.setString(i++, debt.getCaseCategory());
        ps.setString(i++, debt.getCreditStatus());
        setNullableBoolean(ps, i++, debt.getSettlementAllowed());
        ps.setString(i++, debt.getCourt());
        ps.setString(i++, debt.getCourtSignature());
        ps.setString(i++, dateOrNull(debt.getDebtDate()));
        ps.setString(i++, dateOrNull(debt.getServiceStartDate()));
        ps.setString(i++, dateOrNull(debt.getEnforcementStartDate()));
        ps.setString(i++, debt.getDescription());
        ps.setString(i++, (debt.getStatus() != null ? debt.getStatus() : DebtStatus.ACTIVE).name());
        ps.setString(i++, dateOrNull(debt.getClosedAt()));
        ps.setString(i++, debt.getCloseReason());
        return i;
    }

    /**
     * Czy do sprawy zarejestrowano jakąkolwiek wpłatę.
     *
     * <p>Sprawdzenie żyje w repozytorium spraw, bo korzysta z tego samego połączenia
     * i nie wymaga wciągania kolejnej zależności do serwisu — a bez niego nie da się
     * ochronić historii finansowej przed skasowaniem razem ze sprawą.
     */
    public boolean hasPayments(long debtId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM payments WHERE debt_id=? LIMIT 1")) {
            ps.setLong(1, debtId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String dateOrNull(LocalDate date) {
        return date != null ? date.toString() : null;
    }

    private static void setNullableBoolean(PreparedStatement ps, int index, Boolean value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, value ? 1 : 0);
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM debts WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public Optional<Debt> findById(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM debts WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    public List<Debt> findByDebtorId(long debtorId) throws SQLException {
        List<Debt> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM debts WHERE debtor_id=? ORDER BY debt_date DESC")) {
            ps.setLong(1, debtorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    public BigDecimal sumAllRemaining() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(remaining_amount), 0) FROM debts")) {
            return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
        }
    }

    public Set<Long> findDebtorIdsByCreditor(String phrase) throws SQLException {
        Set<Long> ids = new HashSet<>();
        String sql = "SELECT DISTINCT debtor_id FROM debts WHERE lower(creditor) LIKE ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + phrase.toLowerCase() + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong("debtor_id"));
            }
        }
        return ids;
    }

    private Debt map(ResultSet rs) throws SQLException {
        int settlement = rs.getInt("settlement_allowed");
        Boolean settlementAllowed = rs.wasNull() ? null : settlement == 1;

        return Debt.builder()
                .id(rs.getLong("id"))
                .debtorId(rs.getLong("debtor_id"))
                .caseNumber(rs.getString("case_number"))
                .creditor(rs.getString("creditor"))
                .originalCreditor(rs.getString("original_creditor"))
                .portfolio(rs.getString("portfolio"))
                .product(rs.getString("product"))
                .bankAccount(rs.getString("bank_account"))
                .totalAmount(rs.getBigDecimal("total_amount"))
                .remainingAmount(rs.getBigDecimal("remaining_amount"))
                .principalAmount(rs.getBigDecimal("principal_amount"))
                .interestAmount(rs.getBigDecimal("interest_amount"))
                .costAmount(rs.getBigDecimal("cost_amount"))
                .caseCategory(rs.getString("case_category"))
                .creditStatus(rs.getString("credit_status"))
                .settlementAllowed(settlementAllowed)
                .court(rs.getString("court"))
                .courtSignature(rs.getString("court_signature"))
                .debtDate(parseDate(rs.getString("debt_date")))
                .serviceStartDate(parseDate(rs.getString("service_start_date")))
                .enforcementStartDate(parseDate(rs.getString("enforcement_start_date")))
                .description(rs.getString("description"))
                .status(DebtStatus.valueOf(rs.getString("status")))
                .closedAt(parseDate(rs.getString("closed_at")))
                .closeReason(rs.getString("close_reason"))
                .build();
    }

    private static LocalDate parseDate(String value) {
        return value != null ? LocalDate.parse(value) : null;
    }
}
