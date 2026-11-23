package com.abcbank.mobile_banking.controller;

import java.util.List;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.abcbank.mobile_banking.dto.request.CardBlockRequest;
import com.abcbank.mobile_banking.dto.request.CardLimitsUpdateRequest;
import com.abcbank.mobile_banking.dto.request.CardPinChangeRequest;
import com.abcbank.mobile_banking.dto.request.CardRequestCreateRequest;
import com.abcbank.mobile_banking.dto.request.CardUnblockRequest;
import com.abcbank.mobile_banking.dto.response.CardPinOtpResponse;
import com.abcbank.mobile_banking.service.CardService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/cards")
public class CardController {
    private final CardService service;
    public CardController(CardService service) { this.service = service; }

    @GetMapping
    public List<Map<String,Object>> list(Authentication auth) { return service.list(auth.getName()); }

    @GetMapping("/{cardId}")
    public Map<String,Object> details(@PathVariable String cardId, Authentication auth) {
        return service.details(auth.getName(), cardId);
    }

    @PostMapping("/{cardId}/block")
    public Map<String,Object> block(@PathVariable String cardId, @Valid @RequestBody CardBlockRequest request, Authentication auth) {
        return service.block(auth.getName(), cardId, request);
    }

    @PostMapping("/{cardId}/unblock")
    public Map<String,Object> unblock(@PathVariable String cardId, @Valid @RequestBody CardUnblockRequest request, Authentication auth) {
        return service.unblock(auth.getName(), cardId, request);
    }

    @GetMapping("/{cardId}/limits")
    public List<Map<String,Object>> limits(@PathVariable String cardId, Authentication auth) {
        return service.limits(auth.getName(), cardId);
    }

    @PutMapping("/{cardId}/limits")
    public Map<String,Object> updateLimits(@PathVariable String cardId, @Valid @RequestBody CardLimitsUpdateRequest request, Authentication auth) {
        return service.updateLimits(auth.getName(), cardId, request);
    }

    @PostMapping("/{cardId}/pin/send-otp")
    public CardPinOtpResponse sendPinOtp(@PathVariable String cardId, Authentication auth) {
        return service.sendPinOtp(auth.getName(), cardId);
    }

    @PostMapping("/{cardId}/pin")
    public Map<String,Object> changePin(@PathVariable String cardId, @Valid @RequestBody CardPinChangeRequest request, Authentication auth) {
        return service.changePin(auth.getName(), cardId, request);
    }

    @PostMapping("/requests")
    public ResponseEntity<Map<String,Object>> requestCard(@Valid @RequestBody CardRequestCreateRequest request, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.requestCard(auth.getName(), request));
    }

    @GetMapping("/{cardId}/transactions")
    public Map<String,Object> transactions(@PathVariable String cardId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,
            Authentication auth) {
        return service.transactions(auth.getName(), cardId, fromDate, toDate, page, size);
    }
}
