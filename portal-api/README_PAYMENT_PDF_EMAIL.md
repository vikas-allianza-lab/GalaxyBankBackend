# Verified RTGS / NEFT / IMPS payment document

- On successful checker authorisation of `rtgs-neft-imps` / `payments`, the backend renders `src/main/resources/templates/RTGS_NEFT_IMPS_Request_Form.html` to a PDF.
- PDF files are stored under `PAYMENT_DOCUMENTS_DIRECTORY` (default `./payment-documents`) as `PAY-000001.pdf`.
- The document endpoint is authenticated and branch-scoped: `GET /api/v1/request-handling/rtgs-neft-imps/{id}/pdf`. Super Admin access is unrestricted; bank/branch users remain scoped through the existing request-handling identity rules.
- Customer email is looked up using the request's `customer_no` in `MB001001`. Email is sent only when an address is found and SMTP is configured. PDF generation is retained even when email is not configured or delivery fails.

SMTP environment variables (configure them in the backend deployment environment; do not commit credentials):
`SMTP_HOST`, `SMTP_PORT` (default 587), `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH` (default true), `SMTP_STARTTLS` (default true).

Other optional variables: `PAYMENT_DOCUMENTS_DIRECTORY`, `PAYMENT_DOCUMENT_BANK_NAME`.

Dependencies added: Spring Boot mail and OpenHTMLtoPDF.
