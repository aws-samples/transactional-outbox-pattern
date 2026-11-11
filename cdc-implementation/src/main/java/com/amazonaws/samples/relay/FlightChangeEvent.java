// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.samples.relay;

import com.amazonaws.samples.model.Flight;

/**
 * A flight change captured from the DynamoDB table.
 *
 * @param eventId   DynamoDB change record ID; stable across Kinesis redeliveries of the same change
 * @param eventName INSERT or MODIFY
 * @param flight    the new image of the item
 */
public record FlightChangeEvent(String eventId, String eventName, Flight flight) {
}
