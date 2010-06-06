package fiji;

import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class SimpleExecuter {
	protected StreamDumper stdout, stderr;
	protected int exitCode;

	public static interface LineHandler {
		public void handleLine(String line);
	}

	public SimpleExecuter(String[] cmdarray) throws IOException {
		this(cmdarray, null, null, null);
	}

	public SimpleExecuter(String[] cmdarray, File workingDirectory) throws IOException {
		this(cmdarray, null, null, workingDirectory);
	}

	public SimpleExecuter(String[] cmdarray, LineHandler out, LineHandler err) throws IOException {
		this(cmdarray, out, err, null);
	}

	public SimpleExecuter(String[] cmdarray, LineHandler out, LineHandler err, File workingDirectory) throws IOException {
		Process process = Runtime.getRuntime().exec(cmdarray, null, workingDirectory);
		process.getOutputStream().close();
		stderr = getDumper(err, process.getErrorStream());
		stdout = getDumper(out, process.getInputStream());
		for (;;) try {
			exitCode = process.waitFor();
			break;
		} catch (InterruptedException e) { /* ignore */ }
		for (;;) try {
			stdout.join();
			break;
		} catch (InterruptedException e) { /* ignore */ }
		for (;;) try {
			stderr.join();
			break;
		} catch (InterruptedException e) { /* ignore */ }
	}

	public int getExitCode() {
		return exitCode;
	}

	public String getOutput() {
		return stdout.out.toString();
	}

	public String getError() {
		return stderr.out.toString();
	}

	protected class StreamDumper extends Thread {
		protected InputStream in;
		public StringBuffer out;

		public StreamDumper(InputStream in) {
			this.in = in;
			out = new StringBuffer();
			start();
		}

		public void run() {
			byte[] buffer = new byte[16384];
			try {
				for (;;) {
					int count = in.read(buffer);
					if (count < 0)
						break;
					handle(buffer, 0, count);
				}
				in.close();
			} catch (IOException e) {
				stderr.out.append(e.toString());
			}
		}

		protected void handle(byte[] buffer, int offset, int length) {
			out.append(new String(buffer, offset, length));
		}
	}

	protected class LineDumper extends StreamDumper {
		protected LineHandler handler;

		public LineDumper(LineHandler handler, InputStream in) {
			super(in);
			this.handler = handler;
		}

		protected void handle(byte[] buffer, int offset, int length) {
			for (int i = 0; i < length; i++)
				if (buffer[offset + i] == '\n') {
					out.append(new String(buffer, offset, i));
					handler.handleLine(out.toString());
					out.setLength(0);

					offset += i + 1;
					length -= i + 1;
					i = -1;
				}
			out.append(new String(buffer, offset, length));
		}
	}

	protected StreamDumper getDumper(LineHandler handler, InputStream in) {
		return handler != null ? new LineDumper(handler, in) : new StreamDumper(in);
	}

	public static void main(String[] args) {
		try {
			SimpleExecuter executer = new SimpleExecuter(args);
			IJ.log("status: " + executer.getExitCode());
			IJ.log("output: " + executer.getOutput());
			IJ.log("error: " + executer.getError());
		} catch (IOException e) {
			IJ.handleException(e);
		}
	}
}