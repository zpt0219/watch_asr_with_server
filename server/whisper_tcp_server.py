# argparse is used to easily write user-friendly command-line interfaces.
import argparse
# logging is used to track events that happen when the server runs, instead of just using print().
import logging
# os provides a way of using operating system dependent functionality (like checking if we are on Windows).
import os
# socket provides the low-level networking interface for our Python server to talk over TCP.
import socket
# struct is used to pack and unpack binary data (converting Python values into C structs represented as bytes).
import struct
# subprocess allows us to spawn new processes, connect to their input/output/error pipes, and obtain their return codes.
# We use this to execute the external whisper.cpp application.
import subprocess
# tempfile generates temporary files and directories that get automatically cleaned up.
import tempfile
# threading allows us to run multiple operations concurrently in the same process space.
# We create a new Thread for each connected client so one client doesn't block another.
import threading
# wave provides a convenient interface to the WAV sound format.
import wave
# dataclass is a decorator that automatically generates special methods (like __init__) for classes that just store data.
from dataclasses import dataclass
# Path provides object-oriented filesystem paths (easy path manipulation).
from pathlib import Path

# This is a configuration object that will hold all the settings our server needs to run.
@dataclass
class ServerConfig:
    whisper_cli: Path      # Path to the whisper.cpp executable
    model: Path            # Path to the AI model file
    window_seconds: int    # How many seconds of audio we batch before transcribing
    threads: int           # Number of CPU threads to use for whisper (used to speed up inference)

# This class handles taking raw audio bytes, saving them as a .wav file,
# and running the external whisper.cpp program to get the text transcription.
class WhisperRunner:
    def __init__(self, config: ServerConfig):
        # We store the server configuration so we can access paths and settings later.
        self.config = config

    def transcribe(self, pcm_bytes: bytes) -> str:
        # If the audio buffer is completely empty, return an empty string immediately.
        if len(pcm_bytes) == 0:
            return ""
            
        # Create a unique temporary directory to store our audio files.
        # Once the 'with' block finishes, this directory and its contents are automatically deleted!
        with tempfile.TemporaryDirectory(prefix="whisper_srv_") as temp_dir:
            # We define a path for the temporary WAV file inside this new directory.
            wav_path = Path(temp_dir) / "audio.wav"
            
            # Open the temporary file in "wb" (write binary) mode as a WAV file.
            with wave.open(str(wav_path), "wb") as wav_file:
                # 1 channel (Mono audio)
                wav_file.setnchannels(1)
                # 2 bytes per sample (16-bit audio)
                wav_file.setsampwidth(2)
                # 16000 samples per second (16kHz is the standard sample rate required by Whisper)
                wav_file.setframerate(16000)
                # Write our raw PCM (Pulse-Code Modulation) audio bytes into the WAV file structure
                wav_file.writeframes(pcm_bytes)

            # Build the command-line arguments to run the whisper.cpp application.
            # E.g.: whisper-cli.exe -m model.bin -f audio.wav -otxt -t 8
            cmd = [
                str(self.config.whisper_cli),      # The executable
                "-m", str(self.config.model),      # -m flag for the model file
                "-f", str(wav_path),               # -f flag for the audio file to process
                "-otxt",                           # -otxt tells whisper to output the result as a text file
                "-t", str(self.config.threads)     # -t sets the number of CPU threads to use
            ]

            # Log to the console so we can see what the server is doing.
            logging.info("Running whisper.cpp on %s bytes of PCM", len(pcm_bytes))
            
            # Actually run the command. capture_output=True grabs both standard output and standard error,
            # wait for it to finish, and return the result.
            result = subprocess.run(cmd, capture_output=True, text=True)
            
            # Whisper.cpp with '-otxt' will create a file named '<input_filename>.txt'
            txt_path = Path(temp_dir) / "audio.wav.txt"
            
            # If the file wasn't created, something went wrong with the execution.
            if not txt_path.exists():
                # Grab the error messages printed by whisper.cpp
                stderr = result.stderr.strip()
                stdout = result.stdout.strip()
                details = stderr or stdout or "whisper.cpp failed"
                # Crash this specific transcription request with an explicit error
                raise RuntimeError("whisper.cpp did not produce a transcript file: " + details)
            
            # If successful, open the new text file, read the transcription, and return it.
            with open(txt_path, "r", encoding="utf-8") as f:
                return f.read().strip()

# This class handles a single connected client. By extending threading.Thread, 
# each client runs simultaneously without freezing the server for other clients.
class ClientHandler(threading.Thread):
    def __init__(self, connection: socket.socket, address: tuple[str, int], runner: WhisperRunner, config: ServerConfig) -> None:
        super().__init__()
        # The active TCP connection specific to this client
        self.connection = connection
        # The IP address and port of the client
        self.address = address
        # Our Whisper handler instance
        self.runner = runner
        # Our server settings
        self.config = config
        
        # bytearray is a mutable array of bytes. We use it to collect incoming audio fragments.
        self.pcm_buffer = bytearray()
        
        # Calculate exactly how many bytes equal 'window_seconds' of audio.
        # 16000 samples/sec * 2 bytes/sample * N seconds
        self.chunk_size = 16000 * 2 * config.window_seconds 

    # This method is automatically called when we do handler.start()
    def run(self):
        try:
            # We loop forever until the client disconnects.
            while True:
                # 1. Read exactly 1 byte. This is our "Message Type".
                msg_type_data = self._recv_all(1)
                # If we received nothing, the client disconnected. Break the loop.
                if not msg_type_data:
                    break
                # Get the integer value of that 1 byte.
                msg_type = msg_type_data[0]

                # 2. Read exactly 4 bytes. This represents the total length of the upcoming data.
                length_data = self._recv_all(4)
                if not length_data:
                    break
                
                # unpack ">I" converts 4 bytes (Big-Endian Unsigned Integer) back into a Python int.
                length = struct.unpack(">I", length_data)[0]

                # 3. Read the actual payload using the length we just extracted.
                payload = self._recv_all(length)

                # Now decide what to do based on the Message Type we received:
                if msg_type == 0x01: # 0x01 means this is an incoming chunk of Audio (PCM)
                    # Add the new audio bytes to the end of our buffer
                    self.pcm_buffer.extend(payload)
                    
                    # If the buffer is big enough (reaches our configured chunk_size limit)...
                    if len(self.pcm_buffer) >= self.chunk_size:
                        # Process the audio!
                        self._transcribe_and_send()
                        
                elif msg_type == 0x02: # 0x02 means "Flush" (Process whatever audio is left immediately)
                    if len(self.pcm_buffer) > 0:
                        # Process it now, and signal that we should empty the buffer completely
                        self._transcribe_and_send(flush=True)
                        
                elif msg_type == 0x03: # 0x03 means "Close the connection gracefully"
                    break
                
                # If we received a message type we didn't define...
                else:
                    logging.warning("Unknown message type: %s", msg_type)
                    
        except Exception as e:
            # If anything crashes (e.g. abrupt network disconnect), catch it and log it.
            logging.error("Connection error: %s", e)
        finally:
            # The 'finally' block ALWAYS runs. We ensure the connection is closed efficiently.
            self.connection.close()

    # A custom method to send audio to whisper and send text back to the client
    def _transcribe_and_send(self, flush=False):
        try:
            # Convert our mutable bytearray to immutable bytes and pass it to whisper.
            text = self.runner.transcribe(bytes(self.pcm_buffer))
            
            # If whisper found words...
            if text:
                # Send a message back to the client! 0x11 is our custom label for "Response Text"
                # Text must be encoded into raw bytes (using utf-8) before sending over a socket.
                self._send_payload(0x11, text.encode("utf-8"))
            
            # If the client told us to flush, we clear the whole buffer.
            if flush:
                self.pcm_buffer.clear()
            else:
                # If we're continuing to record, we shouldn't throw away ALL audio.
                # Words might get cut in half right when the chunk ends.
                # So we keep the last 1 second (32000 bytes) of audio as "overlap" for the next chunk.
                overlap_bytes = 16000 * 2
                
                # If the buffer is larger than our overlap requirement...
                if len(self.pcm_buffer) > overlap_bytes:
                    # Keep ONLY the final 'overlap_bytes' inside the buffer.
                    # Note: '# type: ignore' suppresses a false-warning from Pyre/Pylance editors.
                    self.pcm_buffer = self.pcm_buffer[-overlap_bytes:]  # type: ignore
                else:
                    # If it's too small, just clear it.
                    self.pcm_buffer.clear()
                    
        except Exception as e:
            logging.error("Transcription error: %s", e)
            # Send an error message back to the client (0x12 means Error Response)
            self._send_payload(0x12, str(e).encode("utf-8"))
            self.pcm_buffer.clear()

    # Helper method to reliably read exactly 'n' bytes from the network socket.
    def _recv_all(self, n: int) -> bytearray:
        data = bytearray()
        # TCP sockets sometimes send things in fragmented pieces. We have to loop
        # until we get the exact number of bytes we were promised.
        while len(data) < n:
            # Try to receive the remaining amount of bytes needed.
            packet = self.connection.recv(n - len(data))
            # If the packet is empty, the connection died.
            if not packet:
                break
            # Append the received pieces to our total data.
            data.extend(packet)
        return data

    # Helper method to package up data and send it back to the client.
    def _send_payload(self, msg_type: int, payload: bytes):
        # We pack our header formatting: 
        # > means Big-Endian (Network standard)
        # B means 1-byte Unsigned Char (for our message type)
        # I means 4-byte Unsigned Integer (for the length of the string payload)
        header = struct.pack(">BI", msg_type, len(payload))
        
        # Send the header followed immediately by the actual payload bytes.
        self.connection.sendall(header + payload)

# The primary server class responsible for managing the main network listening port.
class WhisperTcpServer:
    def __init__(self, config: ServerConfig):
        self.config = config
        self.runner = WhisperRunner(config)

    def serve_forever(self):
        # Create a raw socket object. 
        # AF_INET specifies we are using IPv4 addresses.
        # SOCK_STREAM specifies we are using TCP (a reliable, connection-based protocol).
        server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        
        # SO_REUSEADDR allows us to immediately restart the server on the same port 
        # even if the OS hasn't fully cleaned up the old closed socket yet.
        server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        
        # "0.0.0.0" means we will accept connections from ANY IP address on our local network.
        # 8080 is our chosen port number.
        server_socket.bind(("0.0.0.0", 8080))
        
        # Start listening for incoming connections.
        # '5' is the backlog: OS holds up to 5 pending connections in a queue before rejecting.
        server_socket.listen(5)
        logging.info("Server listening on port 8080")

        # The server loops infinitely, waiting for new devices to connect.
        while True:
            # server_socket.accept() completely pauses (blocks) the program here
            # until a new client tries to connect.
            client, addr = server_socket.accept()
            logging.info("Accepted connection from %s", addr)
            
            # When someone connects, we create a completely new ClientHandler.
            handler = ClientHandler(client, addr, self.runner, self.config)
            # Calling .start() kicks off the handler.run() method in an entirely new background thread!
            handler.start()

# This checks if the file is being run directly as a script (e.g. `python whisper_tcp_server.py`)
if __name__ == "__main__":
    # Configure the format of our console print-outs to include timestamps and severity levels.
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
    
    # Setup our command-line argument parser. This handles '--help' automatically.
    parser = argparse.ArgumentParser(description="Simple TCP server that receives PCM audio and returns whisper.cpp text.")
    
    # Define our command-line flags, providing helpful defaults and datatypes.
    parser.add_argument("--whisper-cli", default=Path("./whisper-cli.exe"), help="Path to the built whisper.cpp CLI executable.", type=Path)
    parser.add_argument("--model", default=Path("./ggml-tiny.en.bin"), help="Path to the whisper.cpp ggml model file.", type=Path)
    parser.add_argument("--threads", type=int, default=8, help="Number of threads to use for whisper.cpp")
    parser.add_argument("--window-seconds", type=int, default=2, help="Seconds of audio to batch before transcribing")
    
    # This reads whatever the user typed in the terminal and maps it into standard variables.
    args = parser.parse_args()

    # We pack all those settings neatly into our custom Config class.
    config = ServerConfig(
        whisper_cli=args.whisper_cli, 
        model=args.model, 
        window_seconds=args.window_seconds,
        threads=args.threads # Note: I wired up the --threads argument you created!
    )

    # Do a quick safety check: warn the user immediately if the executable path is invalid.
    if not config.whisper_cli.exists():
        logging.warning(f"whisper.cpp CLI not found: {config.whisper_cli}. Build whisper.cpp first.")

    # Initialize our server and tell it to start listening indefinitely!
    server = WhisperTcpServer(config)
    server.serve_forever()
