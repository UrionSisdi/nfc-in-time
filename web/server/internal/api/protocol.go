package api

import "strconv"

// Header names carrying the device signature over a /v1/sync body.
const (
	headerKeyID     = "X-NFCIT-Key"
	headerSignature = "X-NFCIT-Signature"
)

// transferMessage is the exact byte string both players sign at the end of a
// contact. The Android client builds it the same way; changing it here without
// changing it there invalidates every transfer in flight.
//
//	nfcit/transfer/v1\n<nonce>\n<from>\n<to>\n<amount>\n<signed_at>\n<prev_hash>
func transferMessage(t transferPayload) []byte {
	msg := make([]byte, 0, 160)
	msg = append(msg, "nfcit/transfer/v1\n"...)
	msg = append(msg, t.Nonce...)
	msg = append(msg, '\n')
	msg = append(msg, t.From...)
	msg = append(msg, '\n')
	msg = append(msg, t.To...)
	msg = append(msg, '\n')
	msg = strconv.AppendInt(msg, t.Amount, 10)
	msg = append(msg, '\n')
	msg = strconv.AppendInt(msg, t.SignedAt, 10)
	msg = append(msg, '\n')
	msg = append(msg, t.PrevHash...)
	return msg
}
