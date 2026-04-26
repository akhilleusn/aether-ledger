package com.aetherledger.exception;

import com.aetherledger.domain.enums.HoldStatus;
import java.util.UUID;

public class HoldNotReleasableException extends LedgerException {
    public HoldNotReleasableException(UUID holdId, HoldStatus currentStatus) {
        super("Hold " + holdId + " cannot be released: current status is " + currentStatus);
    }
}
