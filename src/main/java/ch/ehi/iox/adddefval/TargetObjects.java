/* This file is part of the iox-ili project.
 * For more information, please see <http://www.eisenhutinformatik.ch/iox-ili/>.
 *
 * Copyright (c) 2006 Eisenhut Informatik AG
 * Permission is hereby granted, free of charge, to any person obtaining a 
 * copy of this software and associated documentation files (the "Software"), 
 * to deal in the Software without restriction, including without limitation 
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the 
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included 
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL 
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING 
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER 
 * DEALINGS IN THE SOFTWARE.
 */
package ch.ehi.iox.adddefval;

import java.util.HashMap;
import ch.interlis.ili2c.metamodel.*;

/**
 * @author ce
 * @version $Revision: 1.0 $ $Date: 27.03.2007 $
 */
public class TargetObjects {
	//   map<String oid,String length>
	private HashMap lengths=new HashMap();
	private AttributeDef txtAttr=null;
	public TargetObjects(AttributeDef def){
		txtAttr = def;
	}
	public AttributeDef getTxtAttr() {
		return txtAttr;
	}
	public void setTxtAttr(AttributeDef def) {
		txtAttr = def;
	}
	public String getTextLength(String oid)
	{
		if(lengths.containsKey(oid)){
			return (String)lengths.get(oid);
		}
		return null;
	}
	public void setTextLength(String oid,String len)
	{
		lengths.put(oid,len);
	}

}
