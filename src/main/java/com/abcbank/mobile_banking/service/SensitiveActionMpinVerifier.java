package com.abcbank.mobile_banking.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.abcbank.mobile_banking.exception.AuthException;

/**
 * Runs the existing MPIN verifier in its own transaction so failed-attempt
 * counters commit even when the calling card/payment/profile operation fails.
 */
@Service
public class SensitiveActionMpinVerifier {
    private final CustomerIdentityResolver identities;
    private final AuthService authService;
    private final TransactionTemplate requiresNew;

    public SensitiveActionMpinVerifier(CustomerIdentityResolver identities,
                                       AuthService authService,
                                       PlatformTransactionManager transactionManager) {
        this.identities = identities;
        this.authService = authService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void verify(String principal, String mpin) {
        Long userId = identities.resolve(principal).userId();
        AuthException failure = requiresNew.execute(status -> {
            try {
                authService.verifyMpinForSensitiveAction(userId, mpin);
                return null;
            } catch (AuthException ex) {
                // Catch inside the transaction: AuthService's noRollbackFor rule can commit lock counters.
                return ex;
            }
        });
        if (failure != null) throw failure;
    }
}
