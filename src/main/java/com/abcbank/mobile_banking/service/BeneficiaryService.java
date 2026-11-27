
package com.abcbank.mobile_banking.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.abcbank.mobile_banking.dto.request.BeneficiaryRequest;
import com.abcbank.mobile_banking.dto.response.BeneficiaryResponse;
import com.abcbank.mobile_banking.repository.BeneficiaryRepository;

@Service
public class BeneficiaryService {

    private final BeneficiaryRepository repository;
    private final BeneficiaryOtpService otpService;

    public BeneficiaryService(
            BeneficiaryRepository repository,
            BeneficiaryOtpService otpService) {

        this.repository = repository;
        this.otpService = otpService;
    }

    // Add beneficiary and send OTP to customer's registered email
    @Transactional
    public String addBeneficiary(
            BeneficiaryRequest request,
            String customerNo,
            Long userId) {

        // Beneficiary email is required and will be saved as beneficiary details.
        if (request.beneficiaryEmail() == null
                || request.beneficiaryEmail().isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Beneficiary email is required");
        }

        // Validate transaction limit
        if (request.singleTransactionLimit() == null
                || request.singleTransactionLimit()
                        .compareTo(BigDecimal.ZERO) <= 0) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Single transaction limit must be greater than zero");
        }

        // Get customer's registered email from MB001001.
        // Do not use beneficiaryEmail as the OTP recipient.
        String customerEmail =
                repository.findCustomerRegisteredEmail(customerNo);

        // Save beneficiary with PENDING status
        Long beneficiaryId = repository.saveBeneficiary(
                request.branchCode(),
                customerNo,
                request.beneficiaryName(),
                request.beneficiaryAccount(),
                request.beneficiaryBank(),
                request.beneficiaryIfsc().toUpperCase(),
                request.singleTransactionLimit(),
                request.beneficiaryEmail(),
                userId);

        // Send OTP to customer's registered email
        otpService.generateAndSendOtp(
                beneficiaryId,
                customerNo,
                customerEmail);

        return "Beneficiary submitted successfully. OTP sent to customer's registered email.";
    }

    // Verify beneficiary OTP
    @Transactional
    public String verifyBeneficiaryOtp(
            Long beneficiaryId,
            String customerNo,
            String otp) {

        BeneficiaryResponse beneficiary = repository
                .findByIdAndCustomer(beneficiaryId, customerNo)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Beneficiary not found"));

        if (beneficiary.verified()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Beneficiary is already verified");
        }

        if (!repository.isPendingBeneficiary(
                beneficiaryId, customerNo)) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only pending beneficiaries can be verified");
        }

        boolean verified = otpService.verifyOtp(
                beneficiaryId,
                customerNo,
                otp);

        if (!verified) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid or expired OTP. Maximum 5 attempts allowed.");
        }

        // Activate beneficiary after successful OTP verification
        repository.activateBeneficiary(
                beneficiaryId,
                customerNo);

        return "Beneficiary verified successfully.";
    }

    // Get all customer beneficiaries
    public List<BeneficiaryResponse> getBeneficiaries(
            String customerNo) {

        return repository.findAllByCustomer(customerNo);
    }

    // Update beneficiary
    @Transactional
    public String updateBeneficiary(
            Long beneficiaryId,
            String customerNo,
            String beneficiaryName,
            BigDecimal singleTransactionLimit) {

        BeneficiaryResponse beneficiary = repository
                .findByIdAndCustomer(beneficiaryId, customerNo)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Beneficiary not found"));

        if (!beneficiary.active() || !beneficiary.verified()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only active, verified beneficiaries can be updated");
        }

        // Validate transaction limit
        if (singleTransactionLimit == null
                || singleTransactionLimit.compareTo(BigDecimal.ZERO) <= 0) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Transaction limit must be greater than zero");
        }

        int updated = repository.updateBeneficiaryDetails(
                beneficiaryId,
                customerNo,
                beneficiaryName.trim(),
                singleTransactionLimit);

        if (updated == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Beneficiary could not be updated");
        }

        return "Beneficiary updated successfully.";
    }

    // Deactivate beneficiary
    @Transactional
    public String deactivateBeneficiary(
            Long beneficiaryId,
            String customerNo) {

        BeneficiaryResponse beneficiary = repository
                .findByIdAndCustomer(beneficiaryId, customerNo)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Beneficiary not found"));

        if (!beneficiary.active()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Beneficiary is already inactive");
        }

        repository.deactivateBeneficiary(
                beneficiaryId,
                customerNo);

        return "Beneficiary deleted successfully.";
    }
}
