# Longform BLE protocol

This is a very simple protocol for one-way (client->server) transfer of a single file.

## Identifiers

The service UUID is `4ae29d01-499a-480a-8c41-a82192105125`. There are two characteristics:

- Client->server comms are writes (with response) on `a00e530d-b48b-48c8-aadb-d062a1b91792`
- Server->client comms are indicates on `0c656023-dee6-47c5-9afb-e601dfbdaa1d`

The name of the server device always begins with "EPaper".

## Message serialization

```c
typedef struct  __attribute__((packed)) {
  uint32_t version;
  uint32_t bodyLength;
  uint32_t nameLength;
  char name[];
} lfbt_msg_client_offer; // msg type 0

typedef struct  __attribute__((packed)) {
  uint32_t status;
} lfbt_msg_server_response; // msg type 1

typedef struct  __attribute__((packed)) {
  uint32_t offset;
  char body[];
} lfbt_msg_client_chunk; // msg type 2

typedef union {
  lfbt_msg_client_offer clientOffer;
  lfbt_msg_server_response serverResponse;
  lfbt_msg_client_chunk clientChunk;
} lfbt_message_body;

typedef struct __attribute__((packed)) {
  uint32_t type;
  uint32_t txnId;
  lfbt_message_body body;
} lfbt_message;
```

All integer types are little-endian. Every message is an instance of `lfbt_message` with `type`
set according to which message it is.

## Transmission flow

1. Client connects, picks a random nonzero `txnId`. This `txnId` will be used for all messages in
   both directions.
2. Client sends a `client_offer` message containing the size of the file and its name.
3. Server responds with `server_response`. If `status` is nonzero, an error has occurred and the 
   client should not proceed. Report to user as "Error #{status} occurred".
4. If `status` was zero, the client proceeds by sending the file in chunks, each in
   a `client_chunk`. The client should determine a good chunk size itself based on the MTU, 
   and the chunks don't have to all be the same size. The client should issue these as writes with
   response and should wait for a chunk to be acked before sending the next one. 