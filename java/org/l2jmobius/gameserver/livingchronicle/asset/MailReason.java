package org.l2jmobius.gameserver.livingchronicle.asset;

/**
 * Why a service mail was sent ({@code lc_mail_delivery.reason}, docs/spec/asset-service.md §2.2).
 */
public enum MailReason
{
	PAYOUT,
	REFUND,
	RETURN,
	CORRECTION
}
