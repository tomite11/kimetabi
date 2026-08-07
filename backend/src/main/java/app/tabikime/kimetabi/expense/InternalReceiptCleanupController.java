package app.tabikime.kimetabi.expense;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/receipts")
class InternalReceiptCleanupController {

    private final ReceiptOrphanCleanupService cleanupService;

    InternalReceiptCleanupController(ReceiptOrphanCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @PostMapping("/orphans/cleanup")
    ReceiptOrphanCleanupService.CleanupResult cleanup() {
        return cleanupService.cleanup();
    }
}
