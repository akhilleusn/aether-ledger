package com.aetherledger.exception;

import com.aetherledger.domain.enums.HoldStatus;
import java.util.UUID;

public class HoldNotCapturableException extends LedgerException {
    public HoldNotCapturableException(UUID holdId, HoldStatus currentStatus) {
        super("Hold " + holdId + " cannot be captured: current status is " + currentStatus);
    }
}
