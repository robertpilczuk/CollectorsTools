package com.collectorstools.repository;

import com.collectorstools.db.DatabaseManager;
import com.collectorstools.model.Debtor;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DebtorRepository {

    private final Connection conn;

    public DebtorRepository() {
        this.conn = DatabaseManager.getInstance().getConnection();
    }

    public DebtorRepository(Connection conn) {
        this.conn = conn;
    }

    public Debtor save(Debtor debtor) throws SQLException {
        String sql = """
            INSERT INTO debtors (first_name, last_name, pesel, id_number, phone, email,
                                 street, city, postal_code, secondary_address,
                                 employer_name, employer_address, employer_phone, notes, urgent)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindCommon(ps, debtor, 1);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) debtor.setId(keys.getLong(1));
            }
        }
        return debtor;
    }

    public void update(Debtor debtor) throws SQLException {
        String sql = """
            UPDATE debtors SET first_name=?, last_name=?, pesel=?, id_number=?, phone=?,
                               email=?, street=?, city=?, postal_code=?, secondary_address=?,
                               employer_name=?, employer_address=?, employer_phone=?,
                               notes=?, urgent=?
            WHERE id=?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int next = bindCommon(ps, debtor, 1);
            ps.setLong(next, debtor.getId());
            ps.executeUpdate();
        }
    }

    /**
     * Ustawia wszystkie pola dłużnika poza identyfikatorem, począwszy od podanej pozycji.
     *
     * @return numer kolejnej wolnej pozycji
     */
    private int bindCommon(PreparedStatement ps, Debtor debtor, int from) throws SQLException {
        int i = from;
        ps.setString(i++, debtor.getFirstName());
        ps.setString(i++, debtor.getLastName());
        ps.setString(i++, debtor.getPesel());
        ps.setString(i++, debtor.getIdNumber());
        ps.setString(i++, debtor.getPhone());
        ps.setString(i++, debtor.getEmail());
        ps.setString(i++, debtor.getStreet());
        ps.setString(i++, debtor.getCity());
        ps.setString(i++, debtor.getPostalCode());
        ps.setString(i++, debtor.getSecondaryAddress());
        ps.setString(i++, debtor.getEmployerName());
        ps.setString(i++, debtor.getEmployerAddress());
        ps.setString(i++, debtor.getEmployerPhone());
        ps.setString(i++, debtor.getNotes());
        ps.setInt(i++, debtor.isUrgent() ? 1 : 0);
        return i;
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM debtors WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public Optional<Debtor> findById(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM debtors WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    public List<Debtor> findAll() throws SQLException {
        List<Debtor> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM debtors ORDER BY urgent DESC, last_name, first_name")) {
            while (rs.next()) list.add(map(rs));
        }
        return list;
    }

    public List<Debtor> findByName(String phrase) throws SQLException {
        String sql = "SELECT * FROM debtors WHERE lower(first_name || ' ' || last_name) LIKE ? ORDER BY urgent DESC, last_name, first_name";
        List<Debtor> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + phrase.toLowerCase() + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    public long count() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM debtors")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public List<String> findDistinctCities() throws SQLException {
        List<String> list = new ArrayList<>();
        String sql = "SELECT DISTINCT city FROM debtors WHERE city IS NOT NULL AND city <> '' ORDER BY city";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) list.add(rs.getString("city"));
        }
        return list;
    }

    private Debtor map(ResultSet rs) throws SQLException {
        return Debtor.builder()
                .id(rs.getLong("id"))
                .firstName(rs.getString("first_name"))
                .lastName(rs.getString("last_name"))
                .pesel(rs.getString("pesel"))
                .secondaryAddress(rs.getString("secondary_address"))
                .employerName(rs.getString("employer_name"))
                .employerAddress(rs.getString("employer_address"))
                .employerPhone(rs.getString("employer_phone"))
                .idNumber(rs.getString("id_number"))
                .phone(rs.getString("phone"))
                .email(rs.getString("email"))
                .street(rs.getString("street"))
                .city(rs.getString("city"))
                .postalCode(rs.getString("postal_code"))
                .notes(rs.getString("notes"))
                .urgent(rs.getInt("urgent") == 1)
                .build();
    }
}
