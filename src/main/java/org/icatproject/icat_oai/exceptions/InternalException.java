package org.icatproject.icat_oai.exceptions;

@SuppressWarnings("serial")
public class InternalException extends Exception {

	private int httpStatusCode;

	public InternalException() {
		this.httpStatusCode = 500;
	}

	public int getHttpStatusCode() {
		return httpStatusCode;
	}
}