package app.tabikime.kimetabi.expense;

record PrepareReceiptUploadRequest(
        String contentType,
        long byteSize,
        long version
) {
}
