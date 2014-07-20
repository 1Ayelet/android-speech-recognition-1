package com.Cluster.SpeechRecognizer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javaFlacEncoder.FLACEncoder;
import javaFlacEncoder.FLACStreamOutputStream;
import javaFlacEncoder.StreamConfiguration;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

public class SpeechRecognizer
{
	final String API_KEY = "YOUR_API_KEY";
	final String TAG = "SpeechRecognizer";
	// ������� �������������
	final int SAMPLE_RATE = 8000;
	// ������������ ������������� �������
	final int TIMER_INTERVAL = 100;
	// �����, � ������� �������� ������������� ���������
	final int MAX_ANALYZE_LENGTH = 3000;
	// ����������� ��������� ���� � ��������� ����
	final float VOLUME_RATIO = 1.25f;
	// ������������ ���-�� ������, ����� �������� ������������� ���������
	// ������������
	final float MAX_ERROR_COUNT = 3;
	// ���� � ������� ����� ������� ��� ������, ���������� ������� ������
	final int MAX_NO_ERROR_TIME = 10000;

	// ���������
	VoiceRecognizedListener voiceRecognizedListener = null;
	// ����� ������� �������� ��������� ������ ��������� ������� �����������
	float detectRatio = 0.25f;
	// ������� ����� ������� ����� �������������� ����� � �������������
	int maxSilenceLength = 500;
	// ����������� ����� ����� � �������������
	int minRecordLength = 500; 
	// ������������ ����� ����� � �������������
	int maxRecordLength = 5000;
	// �����
	String language = "en-US";
	// ������������ ���-�� �����������
	int maxResults = 5;

	// ���������, � ������� ������ ������� �����
	int detectLevel = 32767;
	// ����� �� ���� � ������ ������
	boolean recording = false;
	// ����� ������
	int recordLength = 0;
	// ����� ������
	int silenceLength = 0;
	// ������� ����� �� ���������������� �� ���������
	int analyzeLength = 0;
	// ���������� ������ ������������� ������
	int errorCount = 0;
	// �����, � ������� �������� ��� ������
	int noErrorTimer = 0;
	// ������������ ��������� ����, ����� ������� ������
	int maxSilenceLevel = 0;
	// ������������ ���������
	int maxLevel = 0;
	
	AudioRecord aRecorder;
	byte[] buffer;
	ByteArrayOutputStream record = new ByteArrayOutputStream();

	public float getDetectRatio()
	{
		return detectRatio;
	}

	public void setDetectRatio(float detectRatio)
	{
		this.detectRatio = detectRatio;
	}

	public int getMaxSilenceLength()
	{
		return maxSilenceLength;
	}

	public void setMaxSilenceLength(int maxSilenceLength)
	{
		this.maxSilenceLength = maxSilenceLength;
	}

	public int getMinRecordLength()
	{
		return minRecordLength;
	}

	public void setMinRecordLength(int minRecordLength)
	{
		this.minRecordLength = minRecordLength;
	}

	public int getMaxRecordLength()
	{
		return maxRecordLength;
	}

	public void setMaxRecordLength(int maxRecordLength)
	{
		this.maxRecordLength = maxRecordLength;
	}

	public String getLanguage()
	{
		return language;
	}

	public void setLanguage(String language)
	{
		this.language = language;
	}

	public int getMaxResults()
	{
		return maxResults;
	}

	public void setMaxResults(int maxResults)
	{
		this.maxResults = maxResults;
	}

	public void setVoiceRecognizedListener(VoiceRecognizedListener voiceRecognizedListener)
	{
		this.voiceRecognizedListener = voiceRecognizedListener;
	}

	public int getDetectLevel()
	{
		return detectLevel;
	}

	public void start() throws Exception
	{
		if (aRecorder != null)
			return; // ��� ��������
		// ��������� �������
		int audioSource = MediaRecorder.AudioSource.MIC;
		final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
		int nChannels = 1;
		int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
		int bSamples = 16;
		int framePeriod = SAMPLE_RATE * TIMER_INTERVAL / 1000;
		int bufferSize = framePeriod * 2 * bSamples * nChannels / 8;
		if (bufferSize < AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat))
		{
			bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat);
			Log.w(TAG, "Increasing buffer size to " + Integer.toString(bufferSize));
		}

		// ��������� ������ �����
		aRecorder = new AudioRecord(audioSource, SAMPLE_RATE, channelConfig, audioFormat, bufferSize);
		if (aRecorder.getState() != AudioRecord.STATE_INITIALIZED)
		{
			Log.e(TAG, "AudioRecord initialization failed");
			aRecorder = null;
			throw new Exception("AudioRecord initialization failed");
		}
		aRecorder.setRecordPositionUpdateListener(updateListener);
		aRecorder.setPositionNotificationPeriod(framePeriod);
		buffer = new byte[framePeriod * bSamples / 8 * nChannels];
		aRecorder.startRecording();
		// ����� ��� ��������� ������ ������ Android
		aRecorder.read(buffer, 0, buffer.length);
		Log.i(TAG, "Started");
	}

	public void stop()
	{
		if (aRecorder != null)
		{
			aRecorder.stop();
			aRecorder = null;
			Log.i(TAG, "Stopped");
		}
	}

	private AudioRecord.OnRecordPositionUpdateListener updateListener = new AudioRecord.OnRecordPositionUpdateListener()
	{
		@Override
		public void onPeriodicNotification(AudioRecord recorder)
		{
			if (aRecorder == null)
				return; // Stopped
			// ������ ������
			int len = aRecorder.read(buffer, 0, buffer.length);
			int maxAmplitude = 0;
			int detects = 0;
			// ���������� ����� ����������� ������
			for (int p = 0; p < len - 1; p += 2)
			{
				// WTF. ������������ little-endian signed bytes � int
				int level = buffer[p + 1] * 256 + ((buffer[p] >= 0) ? buffer[p] : (256 + buffer[p]));
				int amplitude = Math.abs(level);
				if (amplitude > maxAmplitude)
					maxAmplitude = amplitude;
				if (amplitude > detectLevel)
					detects++;
			}

			// ������ ������� ���������
			// ���������� ������������ ������� ���������
			if (maxAmplitude > maxLevel)
			{
				maxLevel = maxAmplitude;
			}
			// ���� ���������� ����, �� ������� - �� �������� �� ���������
			// ������������?
			if (detects == 0)
			{
				if (analyzeLength >= MAX_ANALYZE_LENGTH)
				{
					if (maxSilenceLevel * VOLUME_RATIO < detectLevel)
					{
						Log.i(TAG, "Decreasing detect level from " + detectLevel + " to " + maxSilenceLevel * VOLUME_RATIO);
						detectLevel = (int) (maxSilenceLevel * VOLUME_RATIO);
					}
					analyzeLength = 0;
					maxSilenceLevel = 0;
				} else
				{
					if (maxAmplitude > maxSilenceLevel)
						maxSilenceLevel = maxAmplitude;
					analyzeLength += TIMER_INTERVAL;
				}
			}

			// Log.d(TAG, "Data: " + len + ", max: " + maxAmplitude +
			// ", detects: " + detects);

			// ���������� �� ������ ���������� ���������� ���������
			// ������������?
			boolean voiceDetected = (detects > len / 2 * detectRatio);

			if (!recording) // ���� ������ �� ���...
			{
				if (voiceDetected) // � �� ���������� ������� ���������
				{
					// ��������� ������
					recording = true;
					recordLength = TIMER_INTERVAL;
					Log.d(TAG, "Voice record started");
				} else
				{
					// ���� ������ �� ���, �� ������ ������ � ������ ���� �����
					record.reset();
					record.write(buffer, 0, len);
					// ������, ������ ����� ���
					noErrorTimer++;
					if (noErrorTimer >= MAX_NO_ERROR_TIME) errorCount = 0;
				}
			}
			if (recording) // ���� ������ ��� (��� �������� ������ ���)
			{
				// ����� ���� � �����
				recordLength += TIMER_INTERVAL;
				record.write(buffer, 0, len);

				// ���� � ���� ��� ����� �� ����������
				// ��� ���� ����� ��� ��� ������� �����
				if (!voiceDetected || (maxRecordLength < recordLength))
				{
					// ������� ��� �����
					silenceLength += TIMER_INTERVAL;
					if ((silenceLength >= maxSilenceLength) || (maxRecordLength < recordLength))
					{
						// ���� ���������� ������
						recording = false;
						Log.d(TAG, "Voice record stopped, length: " + (recordLength - silenceLength));
						if (recordLength - silenceLength >= minRecordLength)
						{
							try
							{
								new Thread(new ProceedRecordThread(record.toByteArray())).start();
							} catch (Exception e)
							{
								e.printStackTrace();
							}
						}
					}
				} else
					silenceLength = 0; // �� ������, �������� ������� ������
			}			
		}

		@Override
		public void onMarkerReached(AudioRecord recorder)
		{
			// �� �����
		}
	};

	class ProceedRecordThread implements Runnable
	{
		byte[] record;

		public ProceedRecordThread(byte[] record)
		{
			this.record = record;
		}

		public void run()
		{
			try
			{
				byte[] flac = flacEncode(record);
				String results[] = request(flac, 5);
				if ((voiceRecognizedListener != null) && (results.length > 0))
					voiceRecognizedListener.onVoiceRecognized(results);
			} catch (Exception e)
			{
				e.printStackTrace();
			}
		}
	}

	byte[] flacEncode(byte[] sampleData) throws IOException
	{
		Log.d(TAG, "Encoding...");
		FLACEncoder flacEncoder = new FLACEncoder();
		ByteArrayOutputStream flacData = new ByteArrayOutputStream();
		FLACStreamOutputStream flacOutputStream = new FLACStreamOutputStream(flacData);
		// FLACFileOutputStream flacOutputStream = new
		// FLACFileOutputStream("/mnt/sdcard/test.flac");
		StreamConfiguration streamConfiguration = new StreamConfiguration();
		streamConfiguration.setSampleRate(SAMPLE_RATE);
		streamConfiguration.setBitsPerSample(16);
		streamConfiguration.setChannelCount(1);
		flacEncoder.setStreamConfiguration(streamConfiguration);
		flacEncoder.setOutputStream(flacOutputStream);
		flacEncoder.openFLACStream();
		int[] sampleDataInt = new int[sampleData.length / 2];
		for (int p = 0; p < sampleData.length - 1; p += 2)
		{
			// WTF, Java? Two little-endian signed bytes to int conversion
			sampleDataInt[p / 2] = sampleData[p + 1] * 256 + ((sampleData[p] >= 0) ? sampleData[p] : (256 + sampleData[p]));
		}
		flacEncoder.addSamples(sampleDataInt, sampleDataInt.length);
		flacEncoder.encodeSamples(sampleDataInt.length, false);
		flacEncoder.encodeSamples(flacEncoder.samplesAvailableToEncode(), true);
		flacOutputStream.close();
		Log.d(TAG, "Encoded");
		return flacData.toByteArray();
	}

	public String[] request(byte[] flac, int maxResults) throws Exception
	{
		Log.d(TAG, "Requesting...");
		final String googleurl = "https://www.google.com/speech-api/v2/recognize?output=json";
		StringBuilder sb = new StringBuilder(googleurl);
		sb.append("&key=" + API_KEY);
		sb.append("&lang=" + language);
		sb.append("&maxresults=" + maxResults);
		sb.append("&pfilter=0"); // ;)

		URL url = new URL(sb.toString());
		URLConnection urlCon = url.openConnection();
		urlCon.setDoOutput(true);
		urlCon.setUseCaches(false);

		urlCon.setRequestProperty("Content-Type", "audio/x-flac; rate=" + SAMPLE_RATE);
		OutputStream outputStream = urlCon.getOutputStream();
		outputStream.write(flac);
		outputStream.close();
		BufferedReader br = new BufferedReader(new InputStreamReader(urlCon.getInputStream(), Charset.forName("UTF-8")));

		StringBuilder result = new StringBuilder();
		String line;
		while ((line = br.readLine()) != null)
		{
			result.append(line);
		}
		br.close();
		// Log.d(TAG, "Response: " + result.toString());
		Pattern regex = Pattern.compile("\"transcript\":\"(.+?)\"");
		Matcher m = regex.matcher(result.toString());
		List<String> results = new ArrayList<String>();
		while (m.find())
		{
			String r = m.group(1);
			results.add(r);
			Log.d(TAG, "Result: " + r);
		}
		// ��������� ������, ����� ������� ������?
		if (results.isEmpty())
		{
			Log.d(TAG, "Google can't understand you");
			errorCount++;
			noErrorTimer = 0;
			if (errorCount >= MAX_ERROR_COUNT) // � ������ �����?
			{
				if (maxLevel > detectLevel)
				{
					Log.i(TAG, "Increasing detect level from " + detectLevel + " to " + maxLevel);
					detectLevel = (int) (maxLevel);
				}
				errorCount = 0;
				maxLevel = 0;
			}
		} else
		{
			errorCount = 0;
			maxLevel = 0;
		}
		return (String[]) results.toArray(new String[results.size()]);
	}

	public interface VoiceRecognizedListener
	{
		void onVoiceRecognized(String[] results);
	}
}
