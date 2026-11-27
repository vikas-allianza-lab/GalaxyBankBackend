# Verified request documents (PDF + email) for every request-handling module

When a checker verifies (authorises) a request, the API now builds a PDF form, stores it in
`PAYMENT_DOCUMENTS_DIRECTORY` (default `./payment-documents`) and emails it to the customer.
The portal then shows **Print PDF** and **Download PDF** in the request's View dialog.

| Module (API key)          | Page                                    | File name        | Template (`src/main/resources/templates/`) |
|---------------------------|-----------------------------------------|------------------|--------------------------------------------|
| `rtgs-neft-imps`          | /request-handling/payments              | `PAY-000001.pdf` | `RTGS_NEFT_IMPS_Request_Form.html`         |
| `cheque-book`             | /request-handling/cheque-book           | `CHQ-000001.pdf` | `Cheque_Book_Request_Form.html`            |
| `stop-payment`            | /request-handling/stop-payment          | `STP-000001.pdf` | `Stop_Payment_Request_Form.html`           |
| `fd-opening`              | /request-handling/fd-opening            | `FD-000001.pdf`  | `FD_Opening_Request_Form.html`             |
| `rd-opening`              | /request-handling/rd-opening            | `RD-000001.pdf`  | `RD_Opening_Request_Form.html`             |
| `loan-emi-payment`        | /request-handling/loan-emi-payment      | `EMI-000001.pdf` | `Loan_EMI_Payment_Request_Form.html`       |
| `closing-accounts`        | /request-handling/closing-accounts      | `FDC-000001.pdf` | `FD_RD_Closure_Request_Form.html`          |
| `standing-instructions`   | /request-handling/standing-instructions | `SI-000001.pdf`  | `Standing_Instruction_Request_Form.html`   |
| `locker-application`      | /request-handling/locker-application    | `LKR-000001.pdf` | `Locker_Application_Form.html`             |
| `fresh-loan-application`  | /request-handling/fresh-loan-application (also `lead-management`) | `LN-000001.pdf` | `Loan_Application_Form.html` |

`opening-accounts` is an alias of `fd-opening`; `payments` is an alias of `rtgs-neft-imps`.

## How it works
- `RequestHandlingController` calls `PaymentDocumentService` (payments) or `RequestDocumentService` (all other modules) right after a successful authorisation. Rejecting a request creates no document.
- `GET /api/v1/request-handling/{module}/{id}/pdf` serves the stored file. It is authenticated and branch-scoped, and only available once the request is verified. If the file was deleted it is re-created (without re-sending the email).
- The PDF is always kept. The email is skipped (and logged) when the customer has no email, or SMTP is not configured; a failed email never blocks verification.
- The customer email comes from `MB001001` (via `customer_no`); locker / loan requests fall back to the email entered on the request.

## Changing a document
Every module has its **own template file** (table above), laid out like the RTGS / NEFT / IMPS form. Edit the wording, labels
and sections directly in that HTML file. Data is inserted with `{{placeholder}}` tokens (values are HTML-escaped).

- Tokens available in every template: `transactionDate`, `bankName`, `branchName`, `accountNumber`, `customerNumber`,
  `customerName`, `mobileNumber`, `transactionNumber`, `officeTransactionDate`, `makerEmployeeNumber`, `checkerEmployeeNumber`.
- Module-specific tokens (e.g. `numberOfLeaves`, `chequeNumber`, `maturityValue`) and the table column that fills each one are
  listed per module in `RequestDocumentForms.SPECS`. To print a new field: add a token to the template and an item to the spec.
- Keep templates well-formed XHTML (close every tag, write `&amp;` for `&`), as for the payment template.

## SMTP (unchanged)
`SMTP_HOST`, `SMTP_PORT` (587), `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH` (true), `SMTP_STARTTLS` (true).
Optional: `PAYMENT_DOCUMENTS_DIRECTORY`, `PAYMENT_DOCUMENT_BANK_NAME`.

## Not covered
KYC documents, nomination updates and positive pay have no backend table / API yet (front-end demo data only), so there is nothing to verify or generate for them.
