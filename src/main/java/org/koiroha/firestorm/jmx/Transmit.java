/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm.jmx;

import java.beans.ConstructorProperties;

public class Transmit {
	private final double read;
	private final double write;

	@ConstructorProperties({"Read", "Write"})
	public Transmit(double read, double write){
		this.read = read;
		this.write = write;
	}

	public double getRead(){
		return read;
	}
	public double getWrite(){
		return write;
	}

}
