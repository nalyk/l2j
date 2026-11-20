# Asset Service, part 3: service mail — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Everything an operation owes a character arrives as system mail, written in the operation's transaction: the
`release` operation (custody → service mail), Mobius adopting the committed message, mail that never expires, the claim
recorded, and the deletion guard that keeps a character with unclaimed service mail from being deleted.

**Architecture:** `AssetTx` gains a characters lock phase (the global row-lock order starts with `characters` rows) and
a mail target for legs; after commit the service asks `MailManager` to adopt each new message. Three core patches
(CP-0014 to CP-0016) are each one small seam calling a Living Chronicle class, so the logic lives and is tested in new
code.

**Spec:** `docs/spec/asset-service.md` revision 12 (§2.1 service mail, §2.2 `lc_mail_delivery`, §4.3 `release`, §6 INV12,
§11.19, §11.21, §11.23).

## Global Constraints

- Service mail: `senderId = -1`, `sendBySystem = 1`, `reqAdena = 0`, `hasAttachments = 'true'`,
  `expiration = 2147483647000`; attachments are `items` rows with `owner_id = -1`, `loc = 'MAIL'`, `loc_data` = message id.
- Writing service mail locks the receiver's `characters` row (`FOR UPDATE`, first in the lock order) and requires it to
  exist with `deletetime = 0`; otherwise the operation aborts with `RECEIVER_GONE` and nothing moves.
- Message ids come from `ObjectIds` (the id manager in production), like every object id.
- Core patches as in part 2: smallest change, CRLF kept (`GameClient.java`, `CharacterDelete.java`? check), ledgered,
  revert-proven.

## Review Focus

1. A receiver marked for deletion between the caller's check and the commit: the characters row lock makes the release
   and the deletion guard serialise. Task 2 and Task 4 test both orders.
2. A message adopted twice (a retry, the census) must not duplicate it in `MailManager`. Task 1.
3. A service mail must still be claimable after a restart: the row survives cleanup (sender -1) and `MailManager.load`
   loads it, never scheduling its expiry. Task 1.
4. A partial claim (inventory full mid-claim) leaves the attachments and no `claimed_at`. Task 3.
5. Mail to a character who no longer exists. Task 2.

---

### Task 1: CP-0014 `MailManager.adopt` and mail that never expires

**Files:** `managers/MailManager.java` (`adopt(int messageId)`: read the row, `putIfAbsent`, tell an online receiver,
register with the deletion task), `taskmanagers/MessageDeletionTaskManager.java` (`add` ignores
`ServiceMail.EXPIRATION`); new `livingchronicle/asset/ServiceMail.java` (constants). Test: `ServiceMailAdoptionTest`.

- [ ] Failing tests: `aCommittedMessageIsAdoptedOnce`; `aServiceMailIsNeverScheduledForExpiry` (adopted and loaded);
  `retailMailStillExpires` (a player mail is still scheduled).

### Task 2: `release`: custody → service mail

**Files:** `AssetTx` (`release(int custody, int receiverCharId, List<Asset> assets, Reason reason, String subject,
String content)`; a characters lock phase before the custody locks; INV12 checked per message), `AssetService`
(adoption after commit), `AssetAbort.Reason.RECEIVER_GONE`. Test: `ReleaseTest`.

- [ ] Failing tests: `aReleaseWritesAServiceMailWithItsAttachmentsDeliveryAndPostings`; `aWholeObjectKeepsItsObjectId`;
  `anAmountBecomesANewAttachmentRow`; `aMarkedOrMissingReceiverAbortsWithNothingMoved`; `theMessageIsInMailManager`.

### Task 3: CP-0015 claims recorded

**Files:** `network/holders/MailMessage.java` (`removeAttachments` calls `ServiceMail.attachmentsCleared(id)` for a
sender -1 message after clearing them); `ServiceMail.attachmentsCleared`. Test: `ServiceMailClaimTest`.

- [ ] Failing tests: `claimingTheAttachmentsSetsClaimedAt` (attachments transferred to the receiver's inventory as
  `RequestPostAttachment` does, then `removeAttachments`); `aPlayerMailHasNoDelivery`.

### Task 4: CP-0016 the deletion guard

**Files:** `livingchronicle/asset/DeletionGuard.java` (`boolean mark(int charId, long deleteTime)`: one transaction
locking the characters row, refusing while unclaimed service mail or a pending mark exists, else setting
`deletetime`), `network/GameClient.java` (`markToDeleteChar` uses it before either path; refusal answers 3),
`network/clientpackets/CharacterDelete.java` (3 → `CharDeleteFail(REASON_DELETION_FAILED)`). Test: `DeletionGuardTest`.

- [ ] Failing tests: `aCharacterWithUnclaimedServiceMailIsNotMarked`; `aCharacterWithAPendingMarkIsNotMarked`;
  `aClaimedMailNoLongerBlocks`; `aMarkedCharacterReceivesNoServiceMail` (Task 2's guard from the other side).
- [ ] Full gate; merge to `main`.
