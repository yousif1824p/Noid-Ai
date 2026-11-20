# Remote SSE framing decoder

`RemoteSseFrameDecoder` is the first isolated client-side building block for the optional remote connector.

It accepts a complete, already-decoded UTF-8 SSE document and produces bounded event/data frames. It accepts only started, delta, completed, and error; supports CRLF, comments, and multiple data lines; rejects duplicate event fields, unsupported fields, malformed or incomplete frames, and unknown event names; and caps a document at 256 KiB, each event at 16 KiB, and the document at 64 frames.

This is not an HTTP client, incremental network-stream parser, JSON schema validator, sequence/request-ID validator, authentication layer, or cancellation implementation. It does not connect to a server or execute tools. The connector remains disabled and unimplemented until those independent layers and their integration tests exist.
