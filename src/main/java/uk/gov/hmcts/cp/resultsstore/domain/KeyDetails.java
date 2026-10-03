package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.UUID;

/**
 * The key details read from a share's payload (FR-018). Each is {@code null} where the payload does
 * not state it.
 *
 * @param courtCentreId    {@code hearing.courtCentre.id}
 * @param courtRoomId      {@code hearing.courtCentre.roomId}
 * @param ljaCode          {@code hearing.courtCentre.lja.ljaCode}
 * @param jurisdictionType {@code hearing.jurisdictionType}, as stated
 * @param sjp              {@code hearing.isSJPHearing}
 * @param groupProceedings {@code hearing.isGroupProceedings}
 * @param youthCourtId     {@code hearing.youthCourt.youthCourtId}, as stated
 * @param reshare          {@code isReshare}
 */
public record KeyDetails(UUID courtCentreId, UUID courtRoomId, String ljaCode, String jurisdictionType,
        Boolean sjp, Boolean groupProceedings, UUID youthCourtId, Boolean reshare) {

    /** No key details: what a share whose extraction failed is stored with. */
    public static final KeyDetails NONE = new KeyDetails(null, null, null, null, null, null, null, null);
}
