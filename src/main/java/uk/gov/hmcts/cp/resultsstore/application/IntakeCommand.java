package uk.gov.hmcts.cp.resultsstore.application;

/**
 * One delivery, as the listener hands it to intake.
 *
 * @param messageId     the broker's {@code JMSMessageID}, {@code null} when it had none
 * @param deliveryCount the broker's {@code JMSXDeliveryCount}, 1 when it was not given
 * @param text          the text of a {@code TextMessage}, {@code null} when it carried none or was not one
 * @param textMessage   whether the message was a {@code TextMessage}
 */
public record IntakeCommand(String messageId, int deliveryCount, String text, boolean textMessage) {

    /**
     * A {@code TextMessage}.
     *
     * @param messageId     the message id, possibly {@code null}
     * @param deliveryCount the delivery count
     * @param text          its text, possibly {@code null}
     * @return the command
     */
    public static IntakeCommand ofText(final String messageId, final int deliveryCount, final String text) {
        return new IntakeCommand(messageId, deliveryCount, text, true);
    }

    /**
     * A message of any other type: recorded as {@code NOT_TEXT_MESSAGE}.
     *
     * @param messageId     the message id, possibly {@code null}
     * @param deliveryCount the delivery count
     * @return the command
     */
    public static IntakeCommand ofNotText(final String messageId, final int deliveryCount) {
        return new IntakeCommand(messageId, deliveryCount, null, false);
    }
}
