package ch.interlis.iox_j.validator;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;


import ch.ehi.basics.logging.EhiLogger;
import ch.ehi.basics.settings.Settings;
import ch.interlis.ili2c.metamodel.AbstractClassDef;
import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.Cardinality;
import ch.interlis.ili2c.metamodel.CompositionType;
import ch.interlis.ili2c.metamodel.CoordType;
import ch.interlis.ili2c.metamodel.Domain;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.FormattedType;
import ch.interlis.ili2c.metamodel.LineForm;
import ch.interlis.ili2c.metamodel.LineType;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.NumericalType;
import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.ObjectType;
import ch.interlis.ili2c.metamodel.PolylineType;
import ch.interlis.ili2c.metamodel.PrecisionDecimal;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.RoleDef;
import ch.interlis.ili2c.metamodel.SurfaceOrAreaType;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.TextType;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Type;
import ch.interlis.ili2c.metamodel.UniquenessConstraint;
import ch.interlis.ili2c.metamodel.Viewable;
import ch.interlis.ili2c.metamodel.ViewableTransferElement;
import ch.interlis.iom.IomConstants;
import ch.interlis.iom.IomObject;
import ch.interlis.iom_j.Iom_jObject;
import ch.interlis.iox.IoxLogEvent;
import ch.interlis.iox.IoxLogging;
import ch.interlis.iox.IoxValidationConfig;
import ch.interlis.iox_j.IoxInvalidDataException;
import ch.interlis.iox_j.logging.LogEventFactory;


public class Validator implements ch.interlis.iox.IoxValidator {
	private ch.interlis.iox.IoxValidationConfig validationConfig=null;
	private IoxLogging errs=null;
	private LogEventFactory errFact=null;
	private TransferDescription td=null;
	private boolean doItfLineTables=false;
	private Settings config=null;
	private boolean validationOff=false;

	public Validator(TransferDescription td, IoxValidationConfig validationConfig,
			IoxLogging errs, LogEventFactory errFact, Settings config) {
		super();
		this.td = td;
		this.validationConfig = validationConfig;
		this.errs = errs;
		this.errFact = errFact;
		this.config=config;
		this.doItfLineTables = false;
		if(doItfLineTables){
			tag2class=ch.interlis.iom_j.itf.ModelUtilities.getTagMap(td);
		}else{
			tag2class=ch.interlis.ili2c.generator.XSDGenerator.getTagMap(td);
		}
		unknownTypev=new HashSet<String>();
		validationOff=ValidationConfig.OFF.equals(this.validationConfig.getConfigValue(ValidationConfig.PARAMETER, ValidationConfig.VALIDATION));
	}

	/** mappings from xml-tags to Viewable|AttributeDef
	 */
	private HashMap<String,Object> tag2class=null;
	/** list of seen but unknown types; maintained to prevent duplicate error messages
	 */
	private HashSet<String> unknownTypev=null;
	
	@Override
	public void close() {
	}

	@Override
	public ch.interlis.iox.IoxValidationDataPool getDataPool() {
		return null;
	}

	@Override
	public IoxLogging getLoggingHandler() {
		return errs;
	}
	@Override
	public void setLoggingHandler(IoxLogging handler) {
		errs=handler;
	}

	@Override
	public void validate(ch.interlis.iox.IoxEvent event) {
		if(validationOff){
			return;
		}
		if(event instanceof ch.interlis.iox.ObjectEvent){
			IomObject iomObj=((ch.interlis.iox.ObjectEvent)event).getIomObject();
			checkObject(iomObj,null);
		}
	}
	
	// Hashset of unique constraints.
	HashMap<AttributeArray, String> seenValues = new HashMap<AttributeArray, String>();
	
	private void checkObject(IomObject iomObj,String attrPath) {
		boolean isObject= attrPath==null;
		if(isObject){
			errFact.setDataObj(iomObj);
		}
		String tag=iomObj.getobjecttag();
		//EhiLogger.debug("tag "+tag);
		Object modelele=tag2class.get(tag);
		if(modelele==null){
			if(!unknownTypev.contains(tag)){
				errs.addEvent(errFact.logErrorMsg("unknown class <{0}>",tag));
			}
			return;
		}
		if(isObject){
			// is it a SURFACE or AREA line table?
			if(doItfLineTables && modelele instanceof AttributeDef){
				checkItfLineTableObject(iomObj,(AttributeDef)modelele);
				return;
			}
		}
		// ASSERT: an ordinary class/table
		Viewable aclass1=(Viewable)modelele;		
		if(isObject){
			// check that object is instance of a concrete class
			if(aclass1.isAbstract()){
				errs.addEvent(errFact.logErrorMsg("Object must be a non-abstract class"));
			}
			if(aclass1 instanceof AbstractClassDef){
				Domain oidType=((AbstractClassDef) aclass1).getOid();
				if(oidType!=null && oidType==td.INTERLIS.UUIDOID){
					String oid=iomObj.getobjectoid();
					if(!isValidUuid(oid)){
						errs.addEvent(errFact.logErrorMsg("TID <{0}> is not a valid UUID", oid));
					}
				}
			}
		}
		
		
		if(isObject){
			String oid=iomObj.getobjectoid();
			// for each UNIQUE
			Iterator attrI=aclass1.iterator();
			// iteriere durch alle attribute, solange es ein n�chstes attr hat
			while (attrI.hasNext()) {
				Object obj1 = attrI.next();
				if(obj1 instanceof UniquenessConstraint){
					UniquenessConstraint uniqueConstraint=(UniquenessConstraint) obj1;
					StringBuilder contentUniqueAttrs = new StringBuilder();
					// get list of attributes, that should be unique
					ArrayList<String> uniqueAttrs=new ArrayList<String>();
					// f�ge das uniqueConstraint zum uniqueAttrs hinzu.
					Iterator iter = uniqueConstraint.getElements().iteratorAttribute();
					while (iter.hasNext()) {
						ObjectPath object = (ObjectPath)iter.next();
						uniqueAttrs.add(object.getLastPathEl().getName());
						contentUniqueAttrs.append(object.getLastPathEl().getName());
					}
					AttributeArray returnValue = checkUnique(iomObj,uniqueAttrs);
					if (returnValue == null){
						// ok
					} else {
						errs.addEvent(errFact.logErrorMsg("Unique is violated! Values {0} already exist in Object: {1}", returnValue.valuesAsString(), seenValues.get(returnValue)));
					}
				}
			}
		}
		
		
		HashSet<String> propNames=new HashSet<String>();
		Iterator iter = aclass1.getAttributesAndRoles2();
		while (iter.hasNext()) {
			ViewableTransferElement obj = (ViewableTransferElement)iter.next();
			if (obj.obj instanceof AttributeDef) {
				AttributeDef attr = (AttributeDef) obj.obj;
				if(!attr.isTransient()){
					Type proxyType=attr.getDomain();
					if(proxyType!=null && (proxyType instanceof ObjectType)){
						// skip implicit particles (base-viewables) of views
					}else{
						propNames.add(attr.getName());
						checkAttrValue(iomObj,attr,null);
					}
				}
			}
			if (isObject && obj.obj instanceof RoleDef) {
				RoleDef role = (RoleDef) obj.obj;
				if (role.getExtending() == null) {

						String refoid = null;
						String roleName = role.getName();
						// a role of an embedded association?
						if (obj.embedded) {
							AssociationDef roleOwner = (AssociationDef) role
									.getContainer();
							if (roleOwner.getDerivedFrom() == null) {
								// not just a link?
								IomObject structvalue = iomObj.getattrobj(
										roleName, 0);
								propNames.add(roleName);
								if (roleOwner.getAttributes().hasNext()
										|| roleOwner
												.getLightweightAssociations()
												.iterator().hasNext()) {
									// TODO handle attributes of link
								}
								if (structvalue != null) {
									refoid = structvalue.getobjectrefoid();
									long orderPos = structvalue
											.getobjectreforderpos();
									if (orderPos != 0) {
										// refoid,orderPos
										// ret.setStringAttribute(roleName,
										// refoid);
										// ret.setStringAttribute(roleName+".orderPos",
										// Long.toString(orderPos));
									} else {
										// refoid
										// ret.setStringAttribute(roleName,
										// refoid);
									}
								} else {
									refoid = null;
								}
							}
						} else {
							IomObject structvalue = iomObj.getattrobj(
									roleName, 0);
							propNames.add(roleName);
							refoid = structvalue.getobjectrefoid();
							long orderPos = structvalue
									.getobjectreforderpos();
							if (orderPos != 0) {
								// refoid,orderPos
								// ret.setStringAttribute(roleName, refoid);
								// ret.setStringAttribute(roleName+".orderPos",
								// Long.toString(orderPos));
							} else {
								// refoid
								// ret.setStringAttribute(roleName, refoid);
							}
						}
						String targetClass = null;
						if (refoid != null) {
							// TODO check target opbject
						}
				}
			 }
		}
		// check if no superfluous properties
		int propc=iomObj.getattrcount();
		for(int propi=0;propi<propc;propi++){
			String propName=iomObj.getattrname(propi);
			if(propName.startsWith("_")){
				// ok, software internal properties start with a '_'
			}else{
				if(!propNames.contains(propName)){
					errs.addEvent(errFact.logErrorMsg("unknown property <{0}>",propName));
				}
			}
		}
		
	}
	
	
	// viewable aClass
	private AttributeArray checkUnique(IomObject currentObject,ArrayList<String>uniqueAttrs) {
		int sizeOfUniqueAttribute = uniqueAttrs.size();
		
		ArrayList<String> accu = new ArrayList<String>();
		for (int i=0;i<sizeOfUniqueAttribute;i++){
			accu.add(currentObject.getattrvalue(uniqueAttrs.get(i)));
		}
		AttributeArray values=new AttributeArray(accu);
		if (seenValues.containsKey(values)){
			return values;
		} else {
			seenValues.put(values, currentObject.getobjectoid());
		}
		return null;
	}

	private void checkAttrValue(IomObject iomObj, AttributeDef attr,String attrPath) {
		 String attrName=attr.getName();
		 String attrQName=attr.getContainer().getScopedName(null)+"."+attrName;
		 if(attrPath==null){
			 attrPath=attrName;
		 }else{
			 attrPath=attrPath+"/"+attrName;
		 }
		 String checkMultiplicity=validationConfig.getConfigValue(attrQName, ValidationConfig.MULTIPLICITY);
		 String checkType=validationConfig.getConfigValue(attrQName, ValidationConfig.TYPE);
		 
			Type type0 = attr.getDomain();
			Type type = attr.getDomainResolvingAliases();
			if (type instanceof CompositionType){
				 int structc=iomObj.getattrvaluecount(attrName);
					if(ValidationConfig.OFF.equals(checkMultiplicity)){
						// skip it
					}else{
						 Cardinality card = ((CompositionType)type).getCardinality();
						 if(structc<card.getMinimum() || structc>card.getMaximum()){
							 logMsg(checkMultiplicity,"Attribute {0} has wrong number of values", attrPath);
						 }
					}
				 for(int structi=0;structi<structc;structi++){
					 IomObject structEle=iomObj.getattrobj(attrName, structi);
						if(ValidationConfig.OFF.equals(checkType)){
							// skip it
						}else{
							String tag=structEle.getobjecttag();
							Object modelele=tag2class.get(tag);
							if(modelele==null){
								if(!unknownTypev.contains(tag)){
									errs.addEvent(errFact.logErrorMsg("unknown class <{0}>",tag));
								}
							}else{
								Viewable structEleClass=(Viewable) modelele;
								Table requiredClass=((CompositionType)type).getComponentType();
								if(!structEleClass.isExtending(requiredClass)){
									 logMsg(checkType,"Attribute {0} requires a structure {1}", attrPath,requiredClass.getScopedName(null));
								}
								if(structEleClass.isAbstract()){
									// check that object is instance of concrete class
									 logMsg(checkType,"Attribute {0} requires a non-abstract structure", attrPath);
								}
							}
						}
					 checkObject(structEle, attrPath+"["+structi+"]");
				 }
			}else{
				if(ValidationConfig.OFF.equals(checkMultiplicity)){
					// skip it
				}else{
					 int structc=iomObj.getattrvaluecount(attrName);
					 if(attr.getDomain().isMandatoryConsideringAliases() && structc==0){
						 logMsg(checkMultiplicity,"Attribute {0} requires a value", attrPath);
					 }
				}
				if(ValidationConfig.OFF.equals(checkType)){
					// skip it
				}else{
					if (attr.isDomainBoolean()) {
						// Value has to be not null and ("true" or "false")
						String valueStr = iomObj.getattrvalue(attrName);
						if (valueStr == null || valueStr.matches("^(true|false)$")) {
							// Value okay, skip it
						} else {
							logMsg(checkType, "value <{0}> is not a BOOLEAN", valueStr);
						}
					} else if (attr.isDomainIliUuid()) {
						// Value is exactly 36 chars long and matches the regex
						String valueStr = iomObj.getattrvalue(attrName);
						if (valueStr == null || isValidUuid(valueStr)) {
								// Value ok, Skip it
						} else {
							logMsg(checkType, "value <{0}> is not a valid UUID", valueStr);
						}
					} else if (attr.isDomainIli1Date()) {
						String valueStr = iomObj.getattrvalue(attrName);
						// Value has to be not null and exactly 8 numbers long
						if (valueStr == null){
							// no value, skip test
						}else if(valueStr.length() == 8) {
							// Value has to be a number
							try {
								int year = Integer.parseInt(valueStr.substring(0, 4));
								int month = Integer.parseInt(valueStr.substring(4, 6));
								int day = Integer.parseInt(valueStr.substring(6, 8));
								// Substring value: year, month and day has to be in valid range
								if (year >= 1582 && year <= 2999 && month >= 01 && month <= 12
										&& day >= 01 && day <= 31) {
								} else {
									logMsg(checkType, "value <{0}> is not in range", valueStr);
								}
							} catch (NumberFormatException numberformatexception) {
								logMsg(checkType, "value <{0}> is not a valid Date", valueStr);
							}
						} else {
							logMsg(checkType, "value <{0}> is not a valid Date", valueStr);
						}
					} else if (attr.isDomainIli2Date()) {
						// Value matches regex and is not null and is in range of type.
						String valueStr = iomObj.getattrvalue(attrName);
						FormattedType subType = (FormattedType) type;
						if (valueStr == null || valueStr.matches(subType.getRegExp()) && subType.isValueInRange(valueStr)) {
							// Value okay, skip it
						} else {
							logMsg(checkType, "value <{0}> is not a valid Date", valueStr);
						}
					} else if (attr.isDomainIli2Time()) {
						// Value is not null and matches 0:0:0.000-23:59:59.999
						String valueStr = iomObj.getattrvalue(attrName);
						FormattedType subType = (FormattedType) type;
						// Min length and max length is added, because of the defined regular expression which does not test the length of the value.
						if (valueStr == null || valueStr.matches(subType.getRegExp()) && subType.isValueInRange(valueStr) && valueStr.length() >= 9 && valueStr.length() <= 12) {
							// Value okay, skip it
						} else {
							logMsg(checkType, "value <{0}> is not a valid Time", valueStr);
						}
					} else if (attr.isDomainIli2DateTime()) {
						// Value is not null
						String valueStr = iomObj.getattrvalue(attrName);
						FormattedType subType = (FormattedType) type;
						// Min length and max length is added, because of the defined regular expression which does not test the length of the value.
						if (valueStr == null || valueStr.matches(subType.getRegExp()) && subType.isValueInRange(valueStr) && valueStr.length() >= 18 && valueStr.length() <= 23) {
							// Value okay, skip it
						} else {
							logMsg(checkType, "value <{0}> is not a valid DateTime", valueStr);
						}
					}else if (type instanceof PolylineType){
						PolylineType polylineType=(PolylineType)type;
						IomObject polylineValue=iomObj.getattrobj(attrName, 0);
						if (polylineValue != null){
							checkPolyline(checkType, polylineType, polylineValue);
						}
					}else if(type instanceof SurfaceOrAreaType){
						SurfaceOrAreaType surfaceOrAreaType=(SurfaceOrAreaType)type;
						IomObject surfaceValue=iomObj.getattrobj(attrName,0);
						if (surfaceValue != null){
							if (surfaceValue.getobjecttag().equals("MULTISURFACE")){
								boolean clipped = surfaceValue.getobjectconsistency()==IomConstants.IOM_INCOMPLETE;
								for(int surfacei=0;surfacei< surfaceValue.getattrvaluecount("surface");surfacei++){
								  if(!clipped && surfacei>0){
								    // unclipped surface with multi 'surface' elements
									  logMsg(checkType,"invalid number of surfaces in COMPLETE basket");
								  }
								  IomObject surface= surfaceValue.getattrobj("surface",surfacei);
								  int boundaryc=surface.getattrvaluecount("boundary");
								  for(int boundaryi=0;boundaryi<boundaryc;boundaryi++){
								    IomObject boundary=surface.getattrobj("boundary",boundaryi);
								    if(boundaryi==0){
								    	// shell
								    }else{
								    	// hole
								    }    
								    for(int polylinei=0;polylinei<boundary.getattrvaluecount("polyline");polylinei++){
								      IomObject polyline=boundary.getattrobj("polyline",polylinei);
								      checkPolyline(checkType, surfaceOrAreaType, polyline);
								      // add line to shell or hole
								    }
								    // add shell or hole to surface
								  }
								}
							} else {
								logMsg(checkType, "unexpected Type "+surfaceValue.getobjecttag()+"; MULTISURFACE expected");
							}
						}
					}else if(type instanceof CoordType){
						// If one dimension of coordType is created, C1 has to be set.
						// if two dimensions of coordType is created, C1 and C2 has to be set.
						// if three dimensions of coordType is created, C1, C2 and C3 has to be set.
						 IomObject coordValue=iomObj.getattrobj(attrName, 0);
						 if(coordValue!=null){
							checkCoordType(checkType, (CoordType)type, coordValue);
						 } 
					}else if(type instanceof NumericType){
						String valueStr=iomObj.getattrvalue(attrName);
						if(valueStr!=null){
							checkNumericType(checkType, (NumericType)type, valueStr);							
						}else{
							IomObject structValue=iomObj.getattrobj(attrName, 0);
							if(structValue!=null){
								logMsg(checkType, "Attribute {0} has an unexpected type {1}",attrPath,structValue.getobjecttag());
							}
						}
					}else if(type instanceof EnumerationType){
						String value=iomObj.getattrvalue(attrName);
						if(value!=null){
							if(!((EnumerationType) type).getValues().contains(value)){
								 logMsg(checkType,"value {0} is not a member of the enumeration", value);
							}
						}else{
							IomObject structValue=iomObj.getattrobj(attrName, 0);
							if(structValue!=null){
								logMsg(checkType, "Attribute {0} has an unexpected type {1}",attrPath,structValue.getobjecttag());
							}
						}
					}else if(type instanceof ReferenceType){
					}else if(type instanceof TextType){
						String value=iomObj.getattrvalue(attrName);
						if(value!=null){
							int maxLength=((TextType) type).getMaxLength();
							if(maxLength!=-1){
								if(value.length()>maxLength){
									 logMsg(checkType,"Attribute {0} is length restricted to {1}", attrPath,Integer.toString(maxLength));
								}
							}
							if(((TextType) type).isNormalized()){
								if(value.indexOf('\n')>=0 || value.indexOf('\r')>=0 || value.indexOf('\t')>=0){
									 logMsg(checkType,"Attribute {0} must not contain control characters", attrPath);
								}
							}
						}else{
							IomObject structValue=iomObj.getattrobj(attrName, 0);
							if(structValue!=null){
								logMsg(checkType, "Attribute {0} has an unexpected type {1}",attrPath,structValue.getobjecttag());
							}
						}
					}
				}
			}
		
	}

	private void checkPolyline(String checkType, LineType polylineType, IomObject polylineValue) {
		if (polylineValue.getobjecttag().equals("POLYLINE")){
			boolean clipped = polylineValue.getobjectconsistency()==IomConstants.IOM_INCOMPLETE;
			for(int sequencei=0;sequencei<polylineValue.getattrvaluecount("sequence");sequencei++){
				if(!clipped && sequencei>0){
					// an unclipped polyline should have only one sequence element
					logMsg(checkType,"invalid number of sequences in COMPLETE basket");
				}
				IomObject sequence=polylineValue.getattrobj("sequence",sequencei);
				LineForm[] lineforms = polylineType.getLineForms();
				HashSet<String> lineformNames=new HashSet<String>();
				for(LineForm lf:lineforms){
					lineformNames.add(lf.getName());
				}
				if(sequence.getobjecttag().equals("SEGMENTS")){
					for(int segmenti=0;segmenti<sequence.getattrvaluecount("segment");segmenti++){
						// segment = all segments which are in the actual sequence.
						IomObject segment=sequence.getattrobj("segment",segmenti);
						if(segment.getobjecttag().equals("COORD")){
							if(lineformNames.contains("STRAIGHTS") || segmenti==0){
								checkCoordType(checkType, (CoordType) polylineType.getControlPointDomain().getType(), segment);
							}else{
								logMsg(checkType, "unexpected COORD");
							}
						} else if (segment.getobjecttag().equals("ARC")){
							if(lineformNames.contains("ARCS") && segmenti>0){
								checkARCSType(checkType, (CoordType) polylineType.getControlPointDomain().getType(), segment);
							}else{
								logMsg(checkType, "unexpected ARC");
							}
						} else {
							logMsg(checkType, "unexpected Type "+segment.getobjecttag());
						}
					}
				} else {
					logMsg(checkType, "unexpected Type "+sequence.getobjecttag());
				}
			}
		} else {
			logMsg(checkType, "unexpected Type "+polylineValue.getobjecttag()+"; POLYLINE expected");
		}
	}

	private void checkCoordType(String checkType, CoordType coordType, IomObject coordValue) {
		if (coordType.getDimensions().length >= 1){
			if (coordValue.getattrvalue("C1") != null){
				checkNumericType(checkType, (NumericType)coordType.getDimensions()[0], coordValue.getattrvalue("C1"));
			} else if (coordValue.getattrvalue("A1") != null) {
				logMsg(checkType, "Not a type of COORD");
			} else {
				logMsg(checkType, "Wrong COORD structure, C1 expected");
			}
		}
		if (coordType.getDimensions().length == 2){
			if (coordValue.getattrvalue("C3") != null){
				logMsg(checkType, "Wrong COORD structure, C3 not expected");
			}
		}
		if (coordType.getDimensions().length >= 2){
			if (coordValue.getattrvalue("C2") != null){
				checkNumericType(checkType, (NumericType)coordType.getDimensions()[1], coordValue.getattrvalue("C2"));
			} else if (coordValue.getattrvalue("A2") != null) {
				logMsg(checkType, "Not a type of COORD");
			} else {
				logMsg(checkType, "Wrong COORD structure, C2 expected");
			}
		}
		if (coordType.getDimensions().length == 3){
			if (coordValue.getattrvalue("C3") != null){
				checkNumericType(checkType, (NumericType)coordType.getDimensions()[2], coordValue.getattrvalue("C3"));
			} else {
				logMsg(checkType, "Wrong COORD structure, C3 expected");
			}
		}
		// check if no superfluous properties
		int propc=coordValue.getattrcount();
		for(int propi=0;propi<propc;propi++){
			String propName=coordValue.getattrname(propi);
			if(propName.startsWith("_")){
				// ok, software internal properties start with a '_'
			}else{
				if(!propName.equals("C1") && !propName.equals("C2") && !propName.equals("C3")){
					errs.addEvent(errFact.logErrorMsg("Wrong COORD structure, unknown property <{0}>",propName));
				}
			}
		}
	}

	private void checkARCSType(String checkType, CoordType coordType, IomObject coordValue) {
		if (coordType.getDimensions().length == 2){
			if (coordValue.getattrvalue("C3") != null){
				logMsg(checkType, "Wrong ARC structure, C3 not expected");
			}
		} else if (coordType.getDimensions().length == 3){
			if (coordValue.getattrvalue("C3") == null){
				logMsg(checkType, "Wrong ARC structure, C3 expected");
			}
		}
		if (coordType.getDimensions().length >= 2){
			if (coordValue.getattrvalue("A1") != null && coordValue.getattrvalue("A2") != null && coordValue.getattrvalue("C1") != null && coordValue.getattrvalue("C2") != null && coordValue.getattrvalue("C3") == null){
				// if in ili, 2 coords are defined, then in coordValue.getDimensions()[0,1] are only 0 or 1 valid.
				checkNumericType(checkType, (NumericType)coordType.getDimensions()[0], coordValue.getattrvalue("A1"));
				checkNumericType(checkType, (NumericType)coordType.getDimensions()[1], coordValue.getattrvalue("A2"));
				checkNumericType(checkType, (NumericType)coordType.getDimensions()[0], coordValue.getattrvalue("C1"));
				checkNumericType(checkType, (NumericType)coordType.getDimensions()[1], coordValue.getattrvalue("C2"));
			} else if (coordValue.getattrvalue("A1") != null && coordValue.getattrvalue("A2") != null && coordValue.getattrvalue("C1") != null && coordValue.getattrvalue("C2") != null && coordValue.getattrvalue("C3") != null){
				//  if in ili, 3 coords are defined, then in coordValue.getDimensions()[0,1,2] are only 0 or 1 or 2 valid.
				checkNumericType(checkType, (NumericType)coordType.getDimensions()[0], coordValue.getattrvalue("A1"));
				checkNumericType(checkType, (NumericType)coordType.getDimensions()[1], coordValue.getattrvalue("A2"));
				checkNumericType(checkType, (NumericType)coordType.getDimensions()[0], coordValue.getattrvalue("C1"));
				checkNumericType(checkType, (NumericType)coordType.getDimensions()[1], coordValue.getattrvalue("C2"));
				checkNumericType(checkType, (NumericType)coordType.getDimensions()[2], coordValue.getattrvalue("C3"));
			// a 2d Arc depends on: A1, A2, C1, C2. 
			} else if (coordValue.getattrvalue("A1") == null || coordValue.getattrvalue("A2") == null || coordValue.getattrvalue("C1") == null || coordValue.getattrvalue("C2") == null){
				logMsg(checkType, "A1, A2, C1, C2 expected! (C3 is expected if 3d)");
			} else {
				logMsg(checkType, "Wrong ARC structure");
			}
		}
		// check if no superfluous properties
		int propc=coordValue.getattrcount();
		for(int propi=0;propi<propc;propi++){
			String propName=coordValue.getattrname(propi);
			if(propName.startsWith("_")){
				// ok, software internal properties start with a '_'
			}else{
				if(!propName.equals("A1") || !propName.equals("A2") || !propName.equals("C1") || !propName.equals("C2") || !propName.equals("C3")){
					// ok.
				} else {
					errs.addEvent(errFact.logErrorMsg("Wrong ARC structure, unknown property <{0}>",propName));
				}
			}
		}
	}

	private void checkNumericType(String checkType, NumericType type, String valueStr) {
		PrecisionDecimal value=null;
		try {
			value=new PrecisionDecimal(valueStr);
		} catch (NumberFormatException e) {
			 logMsg(checkType,"value <{0}> is not a number", valueStr);
		}
		if(value!=null){
			PrecisionDecimal minimum=((NumericType) type).getMinimum();
			PrecisionDecimal maximum=((NumericType) type).getMaximum();
		BigDecimal rounded = new BigDecimal(
				value.toString()).setScale(
				value.getExponent(),
				BigDecimal.ROUND_HALF_UP);
		BigDecimal min_general = new BigDecimal(
				minimum.toString()).setScale(
				minimum.getExponent(),
				BigDecimal.ROUND_HALF_UP);
		BigDecimal max_general = new BigDecimal(
				maximum.toString()).setScale(
				maximum.getExponent(),
				BigDecimal.ROUND_HALF_UP);
		  if (rounded.compareTo (min_general) == -1
			  || rounded.compareTo (max_general) == +1){
				 logMsg(checkType,"value {0} is out of range", valueStr);
		  }
		}
	}

	public boolean isValidUuid(String valueStr) {
		return valueStr.length() == 36 && valueStr.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}?");
	}
	private void logMsg(String checkKind,String msg,String... args){
		 if(ValidationConfig.WARNING.equals(checkKind)){
			 errs.addEvent(errFact.logWarningMsg(msg, args));
		 }else{
			 errs.addEvent(errFact.logErrorMsg(msg, args));
		 }
	}

	private void checkItfLineTableObject(IomObject iomObj, AttributeDef modelele) {
		// TODO Auto-generated method stub
		
	}
}
