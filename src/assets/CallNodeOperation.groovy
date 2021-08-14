import groovy.json.*

def csarID = execution.getVariable("CsarID");
def serviceTemplateID = execution.getVariable("ServiceTemplateID");
def serviceInstanceID = execution.getVariable("ServiceInstanceID");
def ip = serviceInstanceID.substring(7).split("/")[0].split(":")[0];
def nodeTemplateID = execution.getVariable("NodeTemplate");
def nodeInterface = execution.getVariable("Interface");
def operation = execution.getVariable("Operation");
//def inputParamNames = ["DockerEngineURL", "ContainerImage", "ContainerPorts"];
def inputParamNames = execution.getVariableNames();
def outputParamNames = execution.getVariable("OutputParamNames").split(",");
def paramsNeu = "{";
println "alle Variablen";
println inputParamNames;
for(int i in 0..inputParamNames.size()-1){
    if(inputParamNames[i] != null){
    if(inputParamNames[i].startsWith('Input_')){
        def currentParam = inputParamNames[i];
        def param = execution.getVariable(currentParam);
        if(param != null){
          param = param.replace('->', ',');
        
        def type = param.split("!")[0];
        param = param.split("!")[1];
        if(type=='DA'){
           def paramDA = execution.getVariable("instanceDataAPIUrl").split("/servicetemplates")[0];
           def da = param.split("#")[0];
           def fileName = param.split("#")[1];
           def namespace = URLEncoder.encode('http://opentosca.org/artifacttemplates', "UTF-8");
           namespace = URLEncoder.encode(namespace, "UTF-8");
           paramDA= paramDA+'/content/artifacttemplates/'+namespace+'/'+ da + '/files/' + fileName;
           param = paramDA;
        }
        if(type=='VALUE'){
            def dataObject = param.split("#")[0];
            def property = param.split("#")[1];
            // jetzt haben wir die zugehörige nodeinstance
            def nodeInstance = execution.getVariable(dataObject);
            println "das ist das dataobject"
            println nodeInstance;
            def paramValue = execution.getVariable(nodeInstance+property);
            println ""
            param = paramValue;
        }
        def paramName = inputParamNames[i].split('Input_')[1];
        paramsNeu = paramsNeu + '"' + paramName + '" : "' + param + '",';
    }}
    }
}

paramsNeu = paramsNeu + '}';
paramsNeu = paramsNeu.replace(',}', '}');

print "DAS SIND DIE NEUEN PARAMS";
print paramsNeu;
serviceInstanceID = serviceInstanceID.split("/")[serviceInstanceID.split("/").length-1];


def template = '{"invocation-information" : {"csarID" : "$csarID", "serviceTemplateID" : "$serviceTemplateID", "serviceInstanceID" : "$serviceInstanceID", "nodeTemplateID" : "$nodeTemplateID", "interface" : "$nodeInterface", "operation" : "$operation"} , "params" : $params}';
def binding = ["csarID":csarID, "serviceTemplateID":serviceTemplateID, "serviceInstanceID":serviceInstanceID, "nodeTemplateID":nodeTemplateID, "nodeInterface":nodeInterface, "operation":operation, "params":paramsNeu];
print "ALTES TEMP";
print template;
print "ALTES BINDING";
print binding;
def engine = new groovy.text.SimpleTemplateEngine();
def message = engine.createTemplate(template).make(binding).toString();

def url = "http://" + ip + ":8086/ManagementBus/v1/invoker"

println 'ip ist: ' + ip 

def post = new URL(url).openConnection();
post.setRequestMethod("POST");
post.setDoOutput(true);
post.setRequestProperty("Content-Type", "application/xml")
post.setRequestProperty("accept", "application/xml")
post.getOutputStream().write(message.getBytes("UTF-8"));

def status = post.getResponseCode();

println 'status ist: ' + status

if(status != 202){
    execution.setVariable("ErrorDescription", "Received status code " + status + " while invoking interface: " + nodeInterface + " operation: " + operation + " on NodeTemplate with ID: " + nodeTemplateID + "ip: " + ip);
    throw new org.camunda.bpm.engine.delegate.BpmnError("InvalidStatusCode"); 
}

def taskURL =  post.getHeaderField("Location");

println 'taskURL ist: ' + taskURL

while("true"){
    def get = new URL(taskURL).openConnection();
	
	println 'status2: ' + get.getResponseCode();
	
    if(get.getResponseCode() != 200){
        execution.setVariable("ErrorDescription", "Received status code " + status + " while polling for NodeTemplate operation result!");
        throw new org.camunda.bpm.engine.delegate.BpmnError("InvalidStatusCode"); 
    }
    def pollingResult = get.getInputStream().getText();
    println 'pollingResult: ' + pollingResult;    
    def slurper = new JsonSlurper();
    def pollingResultJSON = slurper.parseText(pollingResult);
	println 'pollingResultJSON: ' + pollingResultJSON
    
    if(!pollingResultJSON.status.equals("PENDING")){
        def responseJSON = pollingResultJSON.response;
        outputParamNames.each{ outputParam ->
             execution.setVariable(outputParam, responseJSON.get(outputParam));
        }
        return;
    }
    
    sleep(10000);
}