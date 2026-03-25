# Whisper TCP server

This folder contains a simple Python TCP server that:

1. receives raw PCM audio chunks
2. runs offline transcription or translation with `whisper.cpp`
3. sends the resulting text back to the client

## Protocol

Each frame is:

- 1 byte: message type
- 4 bytes: payload length, big-endian unsigned int
- N bytes: payload

Client message types:

- `0x01` = PCM audio chunk
- `0x02` = flush and transcribe buffered audio now
- `0x03` = close connection

Server message types:

- `0x11` = transcript text, UTF-8
- `0x12` = error text, UTF-8

PCM format expected by the server:

- 16 kHz
- mono
- signed 16-bit little-endian

## Build whisper.cpp CLI

From the workspace root:

### Windows

```powershell
cmake -S third_party/whisper.cpp -B third_party/whisper.cpp/build
cmake --build third_party/whisper.cpp/build --config Release --target whisper-cli
```

### Linux/macOS

```bash
cmake -S third_party/whisper.cpp -B third_party/whisper.cpp/build
cmake --build third_party/whisper.cpp/build --config Release --target whisper-cli
```

## Run the server

Example:

### Windows

```powershell
python server/whisper_tcp_server.py --model third_party/whisper.cpp/models/ggml-tiny.en.bin
```

### Translate to English

```powershell
python server/whisper_tcp_server.py --model third_party/whisper.cpp/models/ggml-base.bin --translate
```

## Notes

- The server batches about 5 seconds of audio before transcribing.
- It keeps 1 second of overlap to reduce boundary issues.
- If you want faster partial results, lower `--window-seconds`.
- If you want translation instead of plain transcription, add `--translate`.
