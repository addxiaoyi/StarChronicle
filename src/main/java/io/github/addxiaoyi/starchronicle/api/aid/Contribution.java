package io.github.addxiaoyi.starchronicle.api.aid;

/** One canonical financial contribution. Native evidence proves money movement; Chronicle
 * intent proves the original authorized actor and campaign attribution. */
public record Contribution(AidRequest request,AidReceipt receipt,String eventId) {}
