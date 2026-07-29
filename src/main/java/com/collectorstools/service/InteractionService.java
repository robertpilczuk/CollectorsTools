package com.collectorstools.service;

import com.collectorstools.exception.BusinessRuleException;
import com.collectorstools.exception.TechnicalException;
import com.collectorstools.model.Interaction;
import com.collectorstools.repository.InteractionRepository;

import java.sql.SQLException;
import java.util.List;

public class InteractionService {

    private final InteractionRepository interactionRepository;

    public InteractionService() {
        this.interactionRepository = new InteractionRepository();
    }

    public InteractionService(InteractionRepository interactionRepository) {
        this.interactionRepository = interactionRepository;
    }

    public Interaction save(Interaction interaction) {
        validate(interaction);
        try {
            return interactionRepository.save(interaction);
        } catch (SQLException e) {
            throw TechnicalException.database("zapisać interakcji", e);
        }
    }

    public void update(Interaction interaction) {
        validate(interaction);
        try {
            interactionRepository.update(interaction);
        } catch (SQLException e) {
            throw TechnicalException.database("zaktualizować interakcji", e);
        }
    }

    public void delete(long id) {
        try {
            interactionRepository.delete(id);
        } catch (SQLException e) {
            throw TechnicalException.database("usunąć interakcji", e);
        }
    }

    public List<Interaction> findByDebtorId(long debtorId) {
        try {
            return interactionRepository.findByDebtorId(debtorId);
        } catch (SQLException e) {
            throw TechnicalException.database("pobrać interakcji", e);
        }
    }

    public int countByDebtorId(long debtorId) {
        try {
            return interactionRepository.countByDebtorId(debtorId);
        } catch (SQLException e) {
            throw TechnicalException.database("policzyć interakcji", e);
        }
    }

    private void validate(Interaction interaction) {
        if (interaction.getDebtorId() == null)
            throw new BusinessRuleException("Interakcja musi być przypisana do dłużnika.");
        if (interaction.getInteractionDate() == null)
            throw new BusinessRuleException("Data interakcji jest wymagana.");
        if (interaction.getContactType() == null)
            throw new BusinessRuleException("Typ kontaktu jest wymagany.");
    }
}
