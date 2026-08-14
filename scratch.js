
        // ==========================================
        // STATE VARIABLES FOR VOICE LOOP & UI
        // ==========================================
        let isSpeakingState = false;
        let isListeningState = false;
        let manualStopRequested = false;
        let speakTimeoutId = null;
        let currentView = 'robot-studio';

        function switchView(viewId, title) {
            currentView = viewId;
            document.querySelectorAll('.view-section').forEach(el => el.classList.remove('active'));
            document.getElementById('view-' + viewId).classList.add('active');
            
            const backBtnContainer = document.getElementById('dynamic-back-btn');
            const navCurrent = document.getElementById('nav-current');
            
            if (viewId === 'robot-studio') {
                backBtnContainer.innerHTML = '';
                navCurrent.classList.add('hidden');
            } else {
                backBtnContainer.innerHTML = `
                    <button onclick="switchView('robot-studio', '')" class="text-cyan-500 hover:text-cyan-300 orbitron text-xs flex items-center gap-1">
                        <svg xmlns="http://www.w3.org/2000/svg" class="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"></polyline></svg>
                        STUDIO DASHBOARD
                    </button>
                `;
                navCurrent.classList.remove('hidden');
                navCurrent.innerText = title;
            }
        }

        // ==========================================
        // PRETRAINED RESPONSES FOR MOVEMENT
        // ==========================================
        const forwardResponses = [
          "Moving forward",
          "Advancing ahead",
          "Rolling forward smoothly",
          "Heading straight ahead",
          "Forward motion activated",
          "Proceeding forward",
          "On the way ahead",
          "Continuing forward"
        ];

        const backwardResponses = [
          "Reversing back",
          "Moving backward",
          "Backing up",
          "Rolling in reverse",
          "Stepping back",
          "Going backward"
        ];

        const leftResponses = [
          "Turning left",
          "Adjusting direction to the left",
          "Rotating left",
          "Moving left",
          "Shifting to the left",
          "Left turn initiated"
        ];

        const rightResponses = [
          "Turning right",
          "Adjusting direction to the right",
          "Rotating right",
          "Moving right",
          "Shifting to the right",
          "Right turn initiated"
        ];

        // ==========================================
        // HARDWARE WEBSOCKET CONNECTION & CONTROL
        // ==========================================
        let robotSocket = null;
        let activeRobotIp = "nova-robot.local";
        let isConnectingWs = false;

        function initHardwareWs() {
            if (window.AndroidLauncher && typeof window.AndroidLauncher.getSavedRobotIP === 'function') {
                activeRobotIp = window.AndroidLauncher.getSavedRobotIP() || "nova-robot.local";
            }
            connectHardwareWs();
        }

        function connectHardwareWs() {
            if (isConnectingWs || (robotSocket && robotSocket.readyState === WebSocket.OPEN)) return;
            isConnectingWs = true;
            
            const wsUrl = `ws://${activeRobotIp}/ws`;
            robotSocket = new WebSocket(wsUrl);
            const statusEl = document.getElementById('ws-status-val');

            robotSocket.onopen = () => {
                isConnectingWs = false;
                if (statusEl) {
                    statusEl.innerText = "ONLINE";
                    statusEl.className = "text-green-400 font-bold";
                }
            };

            robotSocket.onclose = () => {
                isConnectingWs = false;
                robotSocket = null;
                if (statusEl) {
                    statusEl.innerText = "RECONNECTING...";
                    statusEl.className = "text-red-500";
                }
                setTimeout(connectHardwareWs, 3000);
            };

            robotSocket.onerror = (err) => {
                isConnectingWs = false;
                if (statusEl) {
                    statusEl.innerText = "ERROR";
                    statusEl.className = "text-red-500";
                }
            };
        }

        // Helper to push system logs independently of AI chat responses
        function logHardwareAction(msg, isError=false) {
            const hl = document.getElementById('hardware-logs');
            if(hl) {
                const colorClass = isError ? 'text-red-500' : 'text-yellow-400';
                hl.innerHTML += `<div class="${colorClass}">${msg}</div>`;
                const box = document.getElementById('transcript-box');
                if(box) box.scrollTop = box.scrollHeight;
            }
        }

        function sendRobotCmd(payload) {
            if (robotSocket && robotSocket.readyState === WebSocket.OPEN) {
                robotSocket.send(JSON.stringify(payload));
                logHardwareAction(`[ROBOT]: CMD => ${payload.cmd.toUpperCase()}`);
            } else {
                console.warn("Hardware WS offline, queued payload dropped:", payload);
                logHardwareAction(`[ERROR]: Hardware WS Offline. Command '${payload.cmd}' dropped.`, true);
            }
        }

        // ==========================================
        // LOCAL NLP ACTION QUEUE SYSTEM
        // ==========================================
        let actionQueue = [];
        let isExecutingActions = false;

        async function executeActionQueue() {
            if (isExecutingActions || actionQueue.length === 0) return;
            isExecutingActions = true;
            logHardwareAction(`[SYSTEM]: Executing sequential hardware directives...`);

            try {
                while (actionQueue.length > 0) {
                    const action = actionQueue.shift();
                    
                    if (action.type === 'color') {
                        sendRobotCmd({ cmd: 'rgb', r: action.r, g: action.g, b: action.b });
                        await new Promise(res => setTimeout(res, 200));
                    } 
                    else if (action.type === 'servo') {
                        sendRobotCmd({ cmd: 'servo', s: action.s, v: action.v });
                        await new Promise(res => setTimeout(res, 300));
                    }
                    else if (action.type === 'move') {
                        // Send speed first to ensure memory state isn't 0
                        sendRobotCmd({ cmd: 'speed', v: 180 });
                        await new Promise(res => setTimeout(res, 50)); 
                        
                        // Issue move command
                        sendRobotCmd({ cmd: action.dir });
                        
                        if (action.dir !== 'stop' && action.duration > 0) {
                            await new Promise(res => setTimeout(res, action.duration));
                            
                            // TRIPLE REDUNDANT STOP: To prevent the out-of-control looping issue over unreliable networks
                            sendRobotCmd({ cmd: 'stop' });
                            setTimeout(() => sendRobotCmd({ cmd: 'stop' }), 100);
                            setTimeout(() => sendRobotCmd({ cmd: 'stop' }), 250);
                            
                            await new Promise(res => setTimeout(res, 400)); // Momentum arrest pause before next action
                        }
                    }
                    else if (action.type === 'wait') {
                        await new Promise(res => setTimeout(res, action.duration));
                    }
                }
            } catch (err) {
                logHardwareAction(`[ERROR]: Hardware action failed - ${err.message}`, true);
                sendRobotCmd({ cmd: 'stop' }); // Safety failsafe
            } finally {
                logHardwareAction(`[SYSTEM]: Movement sequence complete.`);
                isExecutingActions = false;
            }
        }

        function parseHardwareCommand(text) {
            let lowerText = text.toLowerCase();
            let localIntentFound = false;
            let actionQueueTemp = [];
            let responsePhrases = [];

            // --- 1. PRE-PROCESSING & NORMALIZATION (Fixing STT Typos) ---
            lowerText = lowerText.replace(/\bbed\b/g, 'red');
            lowerText = lowerText.replace(/\bwrite\b/g, 'right').replace(/\bwrit\b/g, 'right');
            // 'white' will be converted to 'right' LATER only if we are in a movement context.

            // --- 2. PRIORITY-BASED INTENT CLASSIFICATION ---
            const isStop = /\b(stop moving|stop|halt)\b/.test(lowerText);
            const isHome = /\b(home|home position|reset|reset position|default position|sleep|wake up)\b/.test(lowerText);
            const isShake = /\b(shake hand|shake hands|handshake|give me your hand)\b/.test(lowerText);
            const isWave = /\b(wave)\b/.test(lowerText);
            const isLightOff = /\b(turn off (the )?(light|led)s?|(light|led)s? off|switch off (the )?(light|led)s?|disable (the )?(light|led)s?)\b/.test(lowerText);
            
            // CONTEXT LOCK: If a body part is detected, we lock into SERVO mode and block base movement
            const isServo = /\b(shoulder|arm|arms|elbow|head|neck|hand|hands)\b/.test(lowerText);
            
            // Color intent
            const isColor = /\b(color|colour|light|led|red|green|blue|yellow|cyan|magenta|white|black)\b/.test(lowerText);

            // --- 3. EXECUTE BASED ON HIGHEST PRIORITY INTENT ---
            if (isStop) {
                actionQueue = []; // Purge global queue immediately
                sendRobotCmd({ cmd: 'stop' });
                setTimeout(() => sendRobotCmd({ cmd: 'stop' }), 100); // Redundancy
                return { found: true, response: "Emergency stop activated." };
            } 
            else if (isLightOff) {
                actionQueueTemp.push({ type: 'color', r: 0, g: 0, b: 0 });
                responsePhrases.push("Turning off the lights");
                localIntentFound = true;
            }
            else if (isHome) {
                actionQueueTemp.push(
                    { type: 'servo', s: 1, v: 75 },  // Right Elbow
                    { type: 'servo', s: 2, v: 90 },  // Right Shoulder
                    { type: 'servo', s: 3, v: 85 },  // Neck / Head
                    { type: 'servo', s: 4, v: 80 },  // Left Shoulder
                    { type: 'servo', s: 5, v: 110 }  // Left Elbow
                );
                responsePhrases.push("Returning to home position");
                localIntentFound = true;
            } 
            else if (isShake) {
                actionQueueTemp.push(
                    { type: 'servo', s: 2, v: 130 }, { type: 'wait', duration: 400 },
                    { type: 'servo', s: 1, v: 50 },  { type: 'wait', duration: 300 },
                    { type: 'servo', s: 1, v: 75 },  { type: 'wait', duration: 300 },
                    { type: 'servo', s: 1, v: 50 },  { type: 'wait', duration: 300 },
                    { type: 'servo', s: 1, v: 75 },  { type: 'wait', duration: 300 },
                    { type: 'servo', s: 2, v: 90 }
                );
                responsePhrases.push("Shaking hands. Nice to meet you");
                localIntentFound = true;
            }
            else if (isWave) {
                 actionQueueTemp.push(
                    { type: 'servo', s: 2, v: 130 }, { type: 'wait', duration: 400 },
                    { type: 'servo', s: 1, v: 50 },  { type: 'wait', duration: 400 },
                    { type: 'servo', s: 1, v: 75 },  { type: 'wait', duration: 400 },
                    { type: 'servo', s: 1, v: 50 },  { type: 'wait', duration: 400 },
                    { type: 'servo', s: 2, v: 90 }
                );
                responsePhrases.push("Waving hello");
                localIntentFound = true;
            }
            else if (isServo) {
                // ==========================================
                // STRICT SERVO CONTEXT (Blocks Base Movement)
                // ==========================================
                
                // 1. Explicit Degrees Parsing
                const jointDegreeRegex = /\b(head|neck|left shoulder|right shoulder|left elbow|right elbow|shoulder|elbow)\s+(?:to\s+|at\s+)?(\d+)(?:\s*(?:deg|degree|degrees))?\b/gi;
                const jointMatches = [...lowerText.matchAll(jointDegreeRegex)];
                for (const match of jointMatches) {
                    let joint = match[1].toLowerCase();
                    let deg = parseInt(match[2]);
                    
                    let s = -1;
                    if (joint === 'right elbow' || (joint === 'elbow' && lowerText.includes('right'))) s = 1;
                    else if (joint === 'right shoulder' || (joint === 'shoulder' && lowerText.includes('right'))) s = 2;
                    else if (joint === 'head' || joint === 'neck') s = 3;
                    else if (joint === 'left shoulder' || (joint === 'shoulder' && lowerText.includes('left'))) s = 4;
                    else if (joint === 'left elbow' || (joint === 'elbow' && lowerText.includes('left'))) s = 5;
                    
                    if (s !== -1) {
                        if (deg < 0) deg = 0;
                        if (deg > 180) deg = 180;
                        actionQueueTemp.push({ type: 'servo', s: s, v: deg });
                        responsePhrases.push(`Setting ${joint} to ${deg} degrees`);
                        localIntentFound = true;
                    }
                }

                // 2. Both Hands Handling
                if (lowerText.match(/\b(raise both hands|raise both arms|both hands up|both arms up|hands up)\b/i)) {
                    actionQueueTemp.push({ type: 'servo', s: 2, v: 145 }, { type: 'servo', s: 4, v: 80 });
                    responsePhrases.push("Raising both hands");
                    localIntentFound = true;
                } else if (lowerText.match(/\b(lower both hands|lower both arms|both hands down|both arms down|put hands down)\b/i)) {
                    actionQueueTemp.push({ type: 'servo', s: 2, v: 90 }, { type: 'servo', s: 4, v: 20 });
                    responsePhrases.push("Lowering both hands");
                    localIntentFound = true;
                }

                // 3. Exact Phrase Matching (Preventing vague triggers)
                const armCommands = [
                    { regex: /\b(raise right arm|right arm up|right shoulder up)\b/i, s: 2, v: 145, msg: "Raising right arm" },
                    { regex: /\b(lower right arm|right arm down|right shoulder down)\b/i, s: 2, v: 90, msg: "Lowering right arm" },
                    { regex: /\b(raise left arm|left arm up|left shoulder up)\b/i, s: 4, v: 80, msg: "Raising left arm" },
                    { regex: /\b(lower left arm|left arm down|left shoulder down)\b/i, s: 4, v: 20, msg: "Lowering left arm" },
                    { regex: /\b(bend right arm|right elbow up)\b/i, s: 1, v: 75, msg: "Bending right arm" },
                    { regex: /\b(straighten right arm|right elbow down)\b/i, s: 1, v: 50, msg: "Straightening right arm" },
                    { regex: /\b(bend left arm|left elbow up)\b/i, s: 5, v: 135, msg: "Bending left arm" },
                    { regex: /\b(straighten left arm|left elbow down)\b/i, s: 5, v: 110, msg: "Straightening left arm" },
                    // Head Commands
                    { regex: /\b(look left|head left)\b/i, s: 3, v: 150, msg: "Turning head left" },
                    { regex: /\b(look right|head right)\b/i, s: 3, v: 35, msg: "Turning head right" },
                    { regex: /\b(look straight|look center|head straight|head center|look up|look down)\b/i, s: 3, v: 90, msg: "Looking straight" }
                ];
                
                for (let ac of armCommands) {
                    if (ac.regex.test(lowerText)) {
                        actionQueueTemp.push({ type: 'servo', s: ac.s, v: ac.v });
                        if (!responsePhrases.includes(ac.msg)) responsePhrases.push(ac.msg);
                        localIntentFound = true;
                    }
                }
            }
            else if (isColor && !/(move|turn|go)\b/.test(lowerText)) {
                // ==========================================
                // STRICT COLOR CONTEXT (Ignore if user said 'turn right' but it heard 'turn white')
                // ==========================================
                const colors = { red: [255,0,0], green: [0,255,0], blue: [0,0,255], yellow: [255,255,0], cyan: [0,255,255], magenta: [255,0,255], white: [255,255,255], black: [0,0,0] };
                for (let c in colors) {
                    if (new RegExp(`\\b${c}\\b`).test(lowerText)) {
                        actionQueueTemp.push({ type: 'color', r: colors[c][0], g: colors[c][1], b: colors[c][2] });
                        responsePhrases.push(`Changing color to ${c}`);
                        localIntentFound = true;
                        break; // Trigger only one color change
                    }
                }
            }
            else {
                // ==========================================
                // MOVEMENT CONTEXT (Only executes if NO body parts were mentioned)
                // ==========================================
                
                // STT Fix: If we reached the movement block, 'white' was misheard for 'right'.
                lowerText = lowerText.replace(/\bwhite\b/g, 'right');

                const wordToNum = { 'one':1, 'two':2, 'three':3, 'four':4, 'five':5, 'six':6, 'seven':7, 'eight':8, 'nine':9, 'ten':10 };
                const moveRegex = /\b(forward|front|back|backward|left|right)\b(?:\s+(?:for\s+)?(\d+(?:\.\d+)?|one|two|three|four|five|six|seven|eight|nine|ten)(?:\s*(sec|seconds|second|s|deg|degree|degrees))?)?/gi;
                
                const matches = [...lowerText.matchAll(moveRegex)];

                for (const match of matches) {
                    let dir = match[1].toLowerCase();
                    let valStr = match[2];
                    let unit = match[3] ? match[3].toLowerCase() : null;
                    
                    if (dir === 'front') dir = 'forward';
                    if (dir === 'back') dir = 'backward';

                    let valNum = null;
                    if (valStr) {
                        if (wordToNum[valStr]) valNum = wordToNum[valStr];
                        else valNum = parseFloat(valStr);
                    }

                    let durationMs = 0;
                    if (valNum !== null) {
                        if (unit && (unit.startsWith('sec') || unit === 's')) {
                            durationMs = valNum * 1000;
                        } else if (unit && unit.startsWith('deg')) {
                            durationMs = Math.round(valNum * (800 / 90));
                        } else {
                            if (dir === 'left' || dir === 'right') {
                                durationMs = Math.round(valNum * (800 / 90)); // assume degrees
                            } else {
                                durationMs = valNum * 1000; // assume seconds
                            }
                        }
                    } else {
                        // Hard defaults if absolutely no numbers provided
                        if (dir === 'forward' || dir === 'backward') durationMs = 2000;
                        if (dir === 'left' || dir === 'right') durationMs = 800;
                    }

                    // SWAP LEFT AND RIGHT COMMANDS (Corrects reverse wiring/firmware behavior)
                    let payloadDir = dir;
                    if (dir === 'left') payloadDir = 'right';
                    else if (dir === 'right') payloadDir = 'left';

                    actionQueueTemp.push({ type: 'move', dir: payloadDir, duration: durationMs });
                    
                    if (dir === 'forward') responsePhrases.push(forwardResponses[Math.floor(Math.random() * forwardResponses.length)]);
                    else if (dir === 'backward') responsePhrases.push(backwardResponses[Math.floor(Math.random() * backwardResponses.length)]);
                    else if (dir === 'left') responsePhrases.push(leftResponses[Math.floor(Math.random() * leftResponses.length)]);
                    else if (dir === 'right') responsePhrases.push(rightResponses[Math.floor(Math.random() * rightResponses.length)]);

                    localIntentFound = true;
                }
            }

            // --- 4. COMPILE AND EXECUTE ---
            if (localIntentFound) {
                actionQueue.push(...actionQueueTemp);
                executeActionQueue();
                
                let aiResponse = "";
                if (responsePhrases.length === 1) {
                    aiResponse = responsePhrases[0] + ".";
                } else if (responsePhrases.length > 1) {
                    aiResponse = responsePhrases[0];
                    for (let i = 1; i < responsePhrases.length; i++) {
                        aiResponse += " then " + responsePhrases[i].toLowerCase();
                    }
                    aiResponse += ".";
                } else {
                    aiResponse = "Executing hardware directives.";
                }
                
                return { found: true, response: aiResponse };
            }
            
            return { found: false };
        }

        // --- Nexus AI Integration ---
        const NEXUS_API_KEY = "rbm_aae517016f05478670c31cdcababb32a";
        let cachedNexusUrl = null;

        async function getNexusApiUrl() {
            if (cachedNexusUrl) return cachedNexusUrl;
            try {
                const res = await fetch("https://rbm-nuc7cjyhn.tail809c52.ts.net/api/collections/ollama/records?page=1&perPage=1");
                const data = await res.json();
                if (data.items && data.items.length > 0) {
                    cachedNexusUrl = data.items[0].ollama_url;
                    const statusBadge = document.getElementById("nexus-status-val");
                    const statusBar = document.getElementById("nexus-bar");
                    if (statusBadge) {
                        statusBadge.innerText = "ONLINE";
                        statusBadge.className = "text-green-400";
                        statusBar.className = "h-full bg-green-400 w-full transition-all";
                    }
                    return cachedNexusUrl;
                }
            } catch (error) {
                const statusBadge = document.getElementById("nexus-status-val");
                if (statusBadge) {
                    statusBadge.innerText = "OFFLINE";
                    statusBadge.className = "text-red-500";
                    document.getElementById("nexus-bar").className = "h-full bg-red-500 w-full transition-all";
                }
            }
            return null;
        }

        async function setSystemPrompt(silent = false) {
            const name = document.getElementById("personaName").value || "nova";
            const role = document.getElementById("robotRole").value || "an intelligent assistant robot";
            const owner = document.getElementById("ownerName").value || "Robomiracle";
            const ceo = document.getElementById("ceoName").value || "Rudresh";
            const cto = document.getElementById("ctoName").value || "Sooraj Sukumaran";

            // Save to localStorage
            localStorage.setItem('robotName', name);
            localStorage.setItem('robotRole', role);
            localStorage.setItem('ownerName', owner);
            localStorage.setItem('ceoName', ceo);
            localStorage.setItem('ctoName', cto);

            let promptTemplate = "";
            try {
                const res = await fetch("system_prompt.txt");
                promptTemplate = await res.text();
            } catch (e) {
                promptTemplate = `You are {{name}}, a physical robot created by Robomiracle. Your role is: {{role}}. The CEO of Robomiracle is {{ceo}}, and the CTO is {{cto}}. Your owner/operator is {{owner}}.`;
            }

            let prompt = promptTemplate
                .replace(/\{\{name\}\}/g, name)
                .replace(/\{\{role\}\}/g, role)
                .replace(/\{\{ceo\}\}/g, ceo)
                .replace(/\{\{cto\}\}/g, cto)
                .replace(/\{\{owner\}\}/g, owner);

            prompt += "\n\nCRITICAL RULE: ALWAYS respond in short, concise sentences. Avoid long paragraphs. Keep your answers brief and to the point.";
            document.getElementById("systemPrompt").value = prompt;
            
            // Clear the visual chat transcript to give the user a clean slate
            document.getElementById('transcript').innerHTML = "";
            localFullResponseText = "";

            // Connect the system prompt properly to the Local AI Engine
            if (window.AndroidLauncher && typeof window.AndroidLauncher.updateLocalAiPersona === 'function') {
                window.AndroidLauncher.updateLocalAiPersona(prompt);
            }
            const btn = document.getElementById("btn-set-prompt");
            try {
                if (!silent && btn) {
                    btn.innerText = "UPDATING DIRECTIVES...";
                    btn.classList.add("animate-pulse", "opacity-80");
                }
                const baseUrl = await getNexusApiUrl();
                if (!baseUrl) throw new Error("Backend Offline");
                await fetch(`${baseUrl}/set-system-prompt`, {
                    method: "POST",
                    headers: { "Content-Type": "application/json", "x-api-key": NEXUS_API_KEY },
                    body: JSON.stringify({ name, system_prompt: prompt })
                });
                if (!silent && btn) {
                    btn.innerText = "PERSONA SAVED!";
                    btn.classList.remove("animate-pulse", "opacity-80");
                    btn.classList.add("bg-green-900/50", "text-green-400", "border-green-500");
                    speakResponse(`updated the persona successfully`, false, true);
                    setTimeout(() => {
                        btn.innerText = "UPDATE PERSONA";
                        btn.classList.remove("bg-green-900/50", "text-green-400", "border-green-500");
                    }, 3000);
                }
            } catch (error) {
                if (!silent && btn) {
                    btn.innerText = "ERROR - RETRY";
                    btn.classList.remove("animate-pulse", "opacity-80");
                    btn.classList.add("bg-red-900/50", "text-red-400", "border-red-500");
                }
            }
        }

        function toggleVoiceSettings() {
            const dropdown = document.getElementById("voice-settings-dropdown");
            dropdown.classList.toggle("hidden");
            if (!dropdown.classList.contains("hidden")) loadVoices();
        }

        function loadVoices() {
            const voiceList = document.getElementById("voice-list");
            if (window.AndroidLauncher && typeof window.AndroidLauncher.getAllVoices === 'function') {
                try {
                    const voices = JSON.parse(window.AndroidLauncher.getAllVoices());
                    if (voices.length === 0) {
                        voiceList.innerHTML = '<div class="text-red-400 text-center py-2">No Voices Found</div>';
                        return;
                    }
                    voiceList.innerHTML = '';
                    voices.forEach(voice => {
                        const btn = document.createElement('button');
                        btn.className = 'text-left px-3 py-2 border-b border-cyan-900/30 flex flex-col gap-1 transition-colors whitespace-normal break-words leading-tight hover:bg-cyan-900/40 relative';
                        if (voice.isActive) btn.classList.add('bg-cyan-900/30', 'border-cyan-400/50');
                        let genderColor = voice.gender === 'Male' ? 'text-blue-400' : (voice.gender === 'Female' ? 'text-pink-400' : 'text-gray-400');
                        btn.innerHTML = `
                            <div class="flex justify-between items-center w-full">
                                <span class="${voice.isActive ? 'text-cyan-300 font-bold' : 'text-cyan-500'}">${voice.name}</span>
                                ${voice.isActive ? '<span class="text-[9px] bg-cyan-500/20 text-cyan-300 px-1 rounded ml-2">ACTIVE</span>' : ''}
                            </div>
                            <div class="flex justify-between text-[10px] opacity-70">
                                <span>${voice.locale}</span><span class="${genderColor} font-bold">${voice.gender}</span>
                            </div>`;
                        btn.onclick = () => { selectVoice(voice.id || voice.name); setTimeout(loadVoices, 200); };
                        voiceList.appendChild(btn);
                    });
                } catch (e) { voiceList.innerHTML = '<div class="text-red-400 text-center py-2">Error loading voices</div>'; }
            } else { voiceList.innerHTML = '<div class="text-yellow-400 text-center py-2">Android TTS <br>Not Available</div>'; }
        }

        function selectVoice(voiceName) {
            if (window.AndroidLauncher && typeof window.AndroidLauncher.setVoice === 'function') {
                window.AndroidLauncher.setVoice(voiceName);
                document.getElementById("voice-settings-dropdown").classList.add("hidden");
            }
        }

        // --- Scraping Animation Logic ---
        let scrapingQueue = [];
        let isScrapingAnimating = false;
        let currentScrapeWrapper = null;
        let scrapeTimeout = null;

        function addScrapingIcon(url) {
            if (!url || scrapingQueue.includes(url)) return;
            scrapingQueue.push(url);
            processScrapingQueue();
        }

        function processScrapingQueue() {
            if (isScrapingAnimating || scrapingQueue.length === 0) return;
            isScrapingAnimating = true;
            const url = scrapingQueue.shift();
            const mic = document.getElementById("mic-icon");
            const marquee = document.getElementById("scraping-marquee");
            if (mic) mic.classList.add("hidden");
            if (marquee) marquee.classList.remove("hidden");
            const wrapper = document.createElement("div");
            wrapper.className = "scrape-icon-wrapper entering";
            const img = document.createElement("img");
            img.src = url;
            img.onerror = function() { this.outerHTML = '<svg class="w-6 h-6 text-cyan-400" viewBox="0 0 24 24" fill="none" stroke="currentColor"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg>'; };
            const scanLine = document.createElement("div");
            scanLine.className = "scan-laser";
            wrapper.appendChild(img); wrapper.appendChild(scanLine);
            if (currentScrapeWrapper && marquee.contains(currentScrapeWrapper)) {
                currentScrapeWrapper.classList.replace("entering", "exiting");
                setTimeout(() => { if (marquee.contains(currentScrapeWrapper)) marquee.removeChild(currentScrapeWrapper); startNewIcon(wrapper, marquee); }, 400);
            } else { startNewIcon(wrapper, marquee); }
        }

        function startNewIcon(wrapper, marquee) {
            marquee.appendChild(wrapper);
            currentScrapeWrapper = wrapper;
            scrapeTimeout = setTimeout(() => {
                if (scrapingQueue.length > 0) {
                    wrapper.classList.replace("entering", "exiting");
                    setTimeout(() => { if (marquee.contains(wrapper)) marquee.removeChild(wrapper); currentScrapeWrapper = null; isScrapingAnimating = false; processScrapingQueue(); }, 500);
                } else { isScrapingAnimating = false; }
            }, 2500);
        }

        function resetScrapingUI() {
            scrapingQueue = []; isScrapingAnimating = false; clearTimeout(scrapeTimeout);
            currentScrapeWrapper = null;
            const mic = document.getElementById("mic-icon");
            const marquee = document.getElementById("scraping-marquee");
            if (mic) mic.classList.remove("hidden");
            if (marquee) { marquee.classList.add("hidden"); marquee.innerHTML = ""; }
        }

        // --- Text Cleaning Utility ---
        function cleanAiText(text) {
            if (!text) return "";
            // Remove emojis and pictographs
            let cleaned = text.replace(/[\p{Emoji_Presentation}\p{Extended_Pictographic}]/gu, '');
            // Remove markdown symbols: *, _, ~, `, #, $, \
            cleaned = cleaned.replace(/[*_~`#$\\]/g, '');
            // Remove consecutive hyphens/dashes (e.g., --, ---) but keep single hyphens for words
            cleaned = cleaned.replace(/-{2,}/g, '');
            // Remove consecutive equals (e.g., ===)
            cleaned = cleaned.replace(/={2,}/g, '');
            return cleaned;
        }

        // --- Chat Interface Logic ---
        async function processAiCommandStream(message) {
            if (!message.trim()) return;

            // Stop any ongoing speech before processing a new command
            stopAllSpeech();

            const ring = document.getElementById('voice-ring');
            const label = document.getElementById('voice-label');
            const box = document.getElementById('transcript-box');
            const txt = document.getElementById('transcript');
            const hwLogs = document.getElementById('hardware-logs');
            const inputField = document.getElementById('ai-text-input');
            const sysName = document.getElementById("personaName")?.value || "nova";
            
            inputField.value = "";
            sentenceBuffer = ""; // Reset buffer
            isAiGenerating = true; // Block mic from starting
            
            // Set UI to processing mode
            setUIMode('processing');
            box.style.opacity = '1';

            // Clear previous hardware logs for this new command interaction
            if (hwLogs) hwLogs.innerHTML = "";
            txt.innerHTML = `<span class="text-cyan-600">You:</span> ${message}\n\n<span class="text-cyan-400 font-bold">NOVA:</span> `;
            
            // 1. Native Hardware Check (Local bypass so it doesn't query the LLM)
            const hwCheck = parseHardwareCommand(message);
            
            if (hwCheck.found) {
                txt.innerHTML += hwCheck.response;
                speakResponse(hwCheck.response);
                resetScrapingUI();
                
                // EARLY RETURN -> Skip sending the text to the slow AI API.
                return; 
            }
            
            // 2. Query Local AI if Active, Else Query Nexus
            try {
                if (isLocalModelReady && window.AndroidLauncher) {
                    localFullResponseText = "";
                    txt.innerHTML += `<span class="text-yellow-400 text-[10px] ml-2">[LOCAL]</span> `;
                    window.AndroidLauncher.sendLocalAiMessage(message);
                    return;
                }

                const baseUrl = await getNexusApiUrl();
                if (!baseUrl) throw new Error("Nexus Backend Offline");
                const response = await fetch(baseUrl + "/chat-stream", {
                    method: "POST",
                    headers: { "Content-Type": "application/json", "x-api-key": NEXUS_API_KEY },
                    body: JSON.stringify({ message, system_name: sysName })
                });
                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                let fullResponseText = ""; let buffer = "";
                while (true) {
                    const { value, done } = await reader.read();
                    if (done) break;
                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n'); buffer = lines.pop();
                    for (let line of lines) {
                        line = line.trim(); if (!line) continue;
                        if (line.startsWith('data:')) line = line.replace(/^data:\s*/, '').trim();
                        if (!line || line === '[DONE]') continue;
                        try {
                            const parsed = JSON.parse(line);
                            if (parsed.error) {
                                if (parsed.error.includes("System prompt not found")) setSystemPrompt(true);
                                fullResponseText += `\n[Error: ${parsed.error}]`;
                            } else if (parsed.type === "status") {
                                label.innerText = parsed.message.toUpperCase();
                                const urlMatch = parsed.message.match(/https?:\/\/[^\s]+/);
                                if (urlMatch) try { addScrapingIcon(`https://www.google.com/s2/favicons?domain=${new URL(urlMatch[0]).hostname}&sz=64`); } catch(e){}
                            } else if (parsed.type === "source" && parsed.icon) {
                                addScrapingIcon(parsed.icon);
                            } else if (parsed.type !== "context") {
                                const rawToken = parsed.response || parsed.message?.content || parsed.content || "";
                                // Clean emojis and markdown symbols to keep UI and TTS clean
                                const token = cleanAiText(rawToken);
                                if (token) { 
                                    resetScrapingUI(); 
                                    fullResponseText += token; 
                                    handleStreamingVoice(token);
                                }
                            }
                            txt.innerHTML = `<span class="text-cyan-600">You:</span> ${message}\n\n<span class="text-cyan-400 font-bold">NOVA:</span> ${fullResponseText}`;
                        } catch(e){}
                    }
                    box.scrollTop = box.scrollHeight;
                }
                resetScrapingUI(); 
                if (sentenceBuffer.trim().length > 0) {
                    speakResponse(sentenceBuffer.trim() + " ", true);
                    sentenceBuffer = "";
                }
                isAiGenerating = false;
                isFirstSpeechChunk = true; // Reset the early-trigger flag for the next response
                if (queuedUtterances <= 0) finishAndStartMic();
            } catch (err) {
                isAiGenerating = false;
                isFirstSpeechChunk = true; // Reset on error
                txt.innerHTML += `\n<span class="text-red-500">[System Error: ${err.message}]</span>`;
                resetVoiceUI();
            }
        }

        // ==========================================
        // VOICE & UI MANAGEMENT
        // ==========================================
        
        function setUIMode(mode) {
            const ring = document.getElementById('voice-ring');
            const label = document.getElementById('voice-label');
            const micIcon = document.getElementById('mic-icon');
            
            ring.classList.remove('voice-active');
            micIcon.classList.remove('text-red-500', 'text-cyan-400');
            
            if (mode === 'listening' || mode === 'speaking') {
                ring.classList.add('voice-active');
                label.innerText = mode === 'listening' ? "LISTENING..." : "SPEAKING...";
                // Change to stop square icon
                micIcon.innerHTML = `<rect x="6" y="6" width="12" height="12" rx="2" ry="2"></rect>`;
                micIcon.classList.add('text-red-500');
            } else if (mode === 'processing') {
                ring.classList.add('voice-active');
                label.innerText = "PROCESSING...";
                micIcon.innerHTML = `<rect x="6" y="6" width="12" height="12" rx="2" ry="2"></rect>`;
                micIcon.classList.add('text-red-500');
            } else {
                // Default mode
                label.innerText = "TAP FOR VOICE CONTROL";
                micIcon.innerHTML = `<path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z" /><path d="M19 10v2a7 7 0 0 1-14 0v-2" /><line x1="12" x2="12" y1="19" y2="22" />`;
                micIcon.classList.add('text-cyan-400');
            }
        }

        function resetVoiceUI() {
            setUIMode('default');
        }

        function goBackAndStop() {
            if (currentView !== 'robot-studio') {
                switchView('robot-studio', '');
                return;
            }
            stopAllSpeech();
            if (window.AndroidLauncher && typeof window.AndroidLauncher.stopAndroidVoiceRecognition === 'function') {
                window.AndroidLauncher.stopAndroidVoiceRecognition();
            } else if (window.activeSpeechRec) {
                window.activeSpeechRec.stop();
            }
            window.location.href = 'nova.html';
        }

        function stopAllSpeech() {
            manualStopRequested = true;
            isSpeakingState = false;
            isFirstSpeechChunk = true; // Reset the early-trigger flag when interrupted
            clearTimeout(speakTimeoutId);
            
            // Abort AI Generation
            if (activeAiStreamController) activeAiStreamController.abort();
            isAiGenerating = false;
            sentenceBuffer = "";
            queuedUtterances = 0;
            
            const waveContainer = document.getElementById('voice-wave');
            const waveBars = waveContainer?.querySelectorAll('.wave-bar') || [];
            waveBars.forEach(bar => bar.classList.remove('active'));

            if (window.AndroidLauncher && typeof window.AndroidLauncher.stopSpeak === 'function') {
                window.AndroidLauncher.stopSpeak();
            } else if (window.AndroidLauncher && typeof window.AndroidLauncher.speak === 'function') {
                window.AndroidLauncher.speak(""); // Interrupt with empty string
            }
            
            if ('speechSynthesis' in window) {
                speechSynthesis.cancel();
            }
            
            resetVoiceUI();
        }

        function handleVoiceTriggerClick() {
            if (isSpeakingState) {
                stopAllSpeech();
            } else if (isListeningState) {
                if (window.AndroidLauncher && typeof window.AndroidLauncher.stopAndroidVoiceRecognition === 'function') {
                    window.AndroidLauncher.stopAndroidVoiceRecognition();
                } else if (window.activeSpeechRec) {
                    window.activeSpeechRec.stop();
                }
                isListeningState = false;
                resetVoiceUI();
            } else {
                startVoiceRecognition();
            }
        }

        let sentenceBuffer = "";
        let queuedUtterances = 0;
        let isAiGenerating = false;

        window.onNativeTtsStart = function() {
            isSpeakingState = true;
            const waveContainer = document.getElementById('voice-wave');
            const waveBars = waveContainer?.querySelectorAll('.wave-bar') || [];
            waveBars.forEach(bar => bar.classList.add('active'));
            setUIMode('speaking');
        };

        window.onNativeTtsDone = function() {
            queuedUtterances--;
            if (queuedUtterances <= 0) {
                queuedUtterances = 0;
                if (!isAiGenerating && !manualStopRequested) {
                    finishAndStartMic();
                } else if (!isAiGenerating) {
                    // Just stop the wave if manual stop was requested
                    isSpeakingState = false;
                    const waveContainer = document.getElementById('voice-wave');
                    const waveBars = waveContainer?.querySelectorAll('.wave-bar') || [];
                    waveBars.forEach(bar => bar.classList.remove('active'));
                    resetVoiceUI();
                }
            }
        };

        function finishAndStartMic() {
            if (!isSpeakingState) return;
            isSpeakingState = false;
            
            const waveContainer = document.getElementById('voice-wave');
            const waveBars = waveContainer?.querySelectorAll('.wave-bar') || [];
            waveBars.forEach(bar => bar.classList.remove('active'));
            resetVoiceUI();
            
            if (!manualStopRequested) {
                setTimeout(() => {
                    if (!manualStopRequested && !isSpeakingState) {
                        startVoiceRecognition();
                    }
                }, 1000);
            }
        }

        let isFirstSpeechChunk = true;

        function handleStreamingVoice(token) {
            if (!token) return;
            sentenceBuffer += token;
            
            while (true) {
                let splitIndex = -1;
                
                // 1. Natural punctuation pause
                const punctMatch = sentenceBuffer.match(/([.!?,\n]+)(\s|$)/);
                const wordCount = sentenceBuffer.trim().split(/\s+/).length;
                
                if (punctMatch) {
                    splitIndex = punctMatch.index + punctMatch[0].length;
                    isFirstSpeechChunk = false;
                } 
                // 2. Zero-latency instant response for the very first chunk (after 3 words)
                else if (isFirstSpeechChunk && wordCount >= 3) {
                    const earlyMatch = sentenceBuffer.match(/^(\S+\s+\S+\s+\S+\s+)/);
                    if (earlyMatch) {
                        splitIndex = earlyMatch[0].length;
                        isFirstSpeechChunk = false;
                    }
                } 
                // 3. Safety flush: if LLM generates a long run-on sentence without punctuation (> 8 words)
                else if (wordCount >= 8) {
                    const safetyMatch = sentenceBuffer.match(/^((?:\S+\s+){8})/);
                    if (safetyMatch) {
                        splitIndex = safetyMatch[0].length;
                    }
                }

                if (splitIndex !== -1) {
                    const clauseToSpeak = sentenceBuffer.substring(0, splitIndex).trim();
                    sentenceBuffer = sentenceBuffer.substring(splitIndex);
                    
                    if (clauseToSpeak.length > 0) {
                        speakResponse(clauseToSpeak + " ", true); // Space added for natural pacing
                    }
                } else {
                    break;
                }
            }
        }

        function speakResponse(text, isQueue = false, preventMic = false) {
            if (!text) return;
            isSpeakingState = true;
            if (preventMic) {
                manualStopRequested = true;
            } else {
                manualStopRequested = false;
            }

            const waveContainer = document.getElementById('voice-wave');
            const waveBars = waveContainer?.querySelectorAll('.wave-bar') || [];
            
            const startWave = () => {
                waveBars.forEach(bar => bar.classList.add('active'));
                setUIMode('speaking');
            };
            
            if (window.AndroidLauncher?.isNativeTtsAvailable?.()) {
                queuedUtterances++;
                if (isQueue && window.AndroidLauncher.speakQueue) {
                    window.AndroidLauncher.speakQueue(text);
                } else {
                    queuedUtterances = 1; // Flush clears previous queue
                    window.AndroidLauncher.speak(text); 
                }
                startWave();
                return;
            }
            
            if (!('speechSynthesis' in window)) return;
            if (!isQueue) {
                speechSynthesis.cancel();
                queuedUtterances = 0;
            }
            queuedUtterances++;
            
            const utterance = new SpeechSynthesisUtterance(text);
            const maleVoice = speechSynthesis.getVoices().find(v => v.name.toLowerCase().includes('male')) || speechSynthesis.getVoices().find(v => v.lang.startsWith('en-'));
            if (maleVoice) utterance.voice = maleVoice;
            
            utterance.onstart = window.onNativeTtsStart; 
            utterance.onend = window.onNativeTtsDone;
            utterance.onerror = window.onNativeTtsDone;
            speechSynthesis.speak(utterance);
        }

        async function startVoiceRecognition() {
            if (isSpeakingState) return;
            manualStopRequested = false;
            isListeningState = true;

            if (window.AndroidLauncher?.startAndroidVoiceRecognition) {
                setUIMode('listening');
                window.onAndroidSpeechResult = (text) => { 
                    isListeningState = false;
                    processAiCommandStream(text); 
                };
                window.onAndroidSpeechError = () => { 
                    isListeningState = false;
                    resetVoiceUI(); 
                };
                window.AndroidLauncher.startAndroidVoiceRecognition();
                return;
            }
            
            const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
            if (!SpeechRecognition) { alert("Recognition not supported."); return; }
            
            const rec = new SpeechRecognition(); 
            rec.lang = 'en-US';
            window.activeSpeechRec = rec;

            rec.onstart = () => setUIMode('listening');
            rec.onresult = (e) => {
                isListeningState = false;
                processAiCommandStream(e.results[0][0].transcript);
            };
            rec.onerror = () => { 
                isListeningState = false;
                resetVoiceUI(); 
            };
            rec.onend = () => { 
                if (isListeningState) {
                    isListeningState = false;
                    resetVoiceUI(); 
                } 
            };
            rec.start();
        }

        // --- Navigation ---
        function switchView(viewName, title = '') {
            // Stop speech automatically when leaving the AI Core
            if (viewName !== 'ai') {
                stopAllSpeech();
            }

            document.querySelectorAll('.view-section').forEach(el => el.classList.remove('active'));
            const target = document.getElementById('view-' + viewName);
            if (target) target.classList.add('active');
            const navCurr = document.getElementById('nav-current');
            const btnContainer = document.getElementById('dynamic-back-btn');
            if (viewName === 'robot-studio') {
                navCurr.classList.add('hidden'); btnContainer.innerHTML = '';
            } else {
                navCurr.classList.remove('hidden'); navCurr.innerText = title;
                btnContainer.innerHTML = `<button onclick="switchView('robot-studio')" class="flex items-center gap-2 text-[10px] orbitron border border-cyan-500/50 px-3 py-1.5 text-cyan-400 hover:bg-cyan-500/20 rounded shadow-md">◄ ROBOT STUDIO</button>`;
            }
        }

        async function fetchRealTelemetry() {
            try {
                let robotIp = window.AndroidLauncher?.getSavedRobotIP?.() || "nova-robot.local";
                // If IP changed, re-establish WS
                if (robotIp !== activeRobotIp) {
                    activeRobotIp = robotIp;
                    if (robotSocket) robotSocket.close();
                }

                let res = await fetch(`http://${robotIp}/status`);
                let data = await res.json();
                document.getElementById('status-ip').innerText = data.ip_address || "N/A";
                document.getElementById('status-ssid').innerText = data.connected_ssid || "N/A";
                if (data.uptime_seconds) {
                    const h = String(Math.floor(data.uptime_seconds / 3600)).padStart(2, '0');
                    const m = String(Math.floor((data.uptime_seconds % 3600) / 60)).padStart(2, '0');
                    const s = String(data.uptime_seconds % 60).padStart(2, '0');
                    document.getElementById('uptime-display').innerText = `${h}:${m}:${s}`;
                }
            } catch(e){}
            document.getElementById('clock').innerText = new Date().toTimeString().split(' ')[0];
        }

        function checkUpdate() {
            const term = document.getElementById('update-terminal');
            const badge = document.getElementById('update-badge');
            term.classList.remove('hidden');
            term.innerText = "> Contacting Robomiracle Cloud...";
            badge.innerText = 'CHECKING...';
            badge.className = 'bg-yellow-900/80 text-[9px] px-2 py-1 rounded orbitron text-yellow-300';
            if (window.AndroidLauncher && window.AndroidLauncher.triggerUpdateCheck) {
                window.AndroidLauncher.triggerUpdateCheck();
                // If no popup in 5s, assume up to date
                setTimeout(() => {
                    if (!document.getElementById('global-ota-popup')) {
                        term.innerText = "> System is up to date.";
                        badge.innerText = 'UP TO DATE';
                        badge.className = 'bg-cyan-900/80 text-[9px] px-2 py-1 rounded orbitron';
                    }
                }, 6000);
            } else {
                setTimeout(() => { term.innerText = "> Up to date."; }, 800);
            }
        }

        // Only needed to update the local badge when the global popup is injected
        window.showUpdateAvailable = function(version, notes) {
            const badge = document.getElementById('update-badge');
            if (badge) {
                badge.innerText = 'UPDATE AVAILABLE';
                badge.className = 'bg-green-900/80 text-[9px] px-2 py-1 rounded orbitron text-green-300 animate-pulse';
            }
            const term = document.getElementById('update-terminal');
            if (term) {
                term.classList.remove('hidden');
                term.innerText = "> New version " + version + " found!";
            }
        };

        function emergencyStop() { document.getElementById('modal-overlay').style.display = 'flex'; }
        function closeModal() { document.getElementById('modal-overlay').style.display = 'none'; }

        function initPersonaInputs() {
            const robotName = localStorage.getItem('robotName') || 'nova';
            const robotRole = localStorage.getItem('robotRole') || 'an intelligent assistant robot';
            const ownerName = localStorage.getItem('ownerName') || 'Robomiracle';
            const ceoName = localStorage.getItem('ceoName') || 'Rudresh';
            const ctoName = localStorage.getItem('ctoName') || 'Sooraj Sukumaran';

            document.getElementById("personaName").value = robotName;
            document.getElementById("robotRole").value = robotRole;
            document.getElementById("ownerName").value = ownerName;
            document.getElementById("ceoName").value = ceoName;
            document.getElementById("ctoName").value = ctoName;
        }

        let isLocalModelReady = false;
        let localFullResponseText = "";

        function checkLocalModelStatus() {
            if (window.AndroidLauncher && window.AndroidLauncher.checkLocalModelExists()) {
                document.getElementById('local-model-badge').innerText = 'DOWNLOADED';
                document.getElementById('local-model-badge').className = 'bg-cyan-900/80 text-[9px] px-2 py-1 rounded orbitron';
                const initBtn = document.getElementById('btn-init-model');
                initBtn.classList.remove('opacity-50', 'pointer-events-none');
                initBtn.classList.add('border-green-500', 'text-green-500', 'hover:bg-green-500/20');
                document.getElementById('btn-download-model').classList.add('hidden');
            }
        }

        function downloadLocalModel() {
            const url = document.getElementById('localModelUrl').value;
            const bar = document.getElementById('model-download-bar');
            const logs = document.getElementById('local-model-logs');
            const btn = document.getElementById('btn-download-model');
            
            bar.classList.remove('hidden');
            logs.classList.remove('hidden');
            logs.innerText = "> Connecting to server...";
            btn.innerText = "DOWNLOADING...";
            btn.classList.add('animate-pulse', 'opacity-50', 'pointer-events-none');
            
            if (window.AndroidLauncher) window.AndroidLauncher.startLocalModelDownload(url);
        }

        window.onModelDownloadProgress = function(pct) {
            document.getElementById('model-download-progress').style.width = pct + '%';
            document.getElementById('local-model-logs').innerText = "> Downloading... " + pct + "%";
            if (pct >= 100) {
                setTimeout(() => {
                    document.getElementById('model-download-bar').classList.add('hidden');
                    document.getElementById('local-model-logs').innerText = "> Download Complete.";
                    const btn = document.getElementById('btn-download-model');
                    btn.innerText = "DOWNLOADED";
                    btn.classList.remove('animate-pulse');
                    btn.classList.add('hidden');
                    checkLocalModelStatus();
                }, 1000);
            }
        };

        window.onModelDownloadError = function(err) {
            document.getElementById('local-model-logs').innerText = "> Error: " + err;
            document.getElementById('local-model-logs').className = "fira-code text-[10px] text-red-500 mt-2 mb-3 whitespace-pre-line";
            const btn = document.getElementById('btn-download-model');
            btn.innerText = "DOWNLOAD FAILED";
            btn.classList.remove('animate-pulse', 'opacity-50', 'pointer-events-none');
        };



        function initLocalModel() {
            const useGpu = document.getElementById('localModelBackend').value === 'gpu';
            const btn = document.getElementById('btn-init-model');
            const logs = document.getElementById('local-model-logs');
            logs.classList.remove('hidden');
            logs.innerText = "> Initializing LiteRT-LM Engine...";
            btn.innerText = "INITIALIZING...";
            btn.classList.add('animate-pulse', 'opacity-50', 'pointer-events-none');
            
            if (window.AndroidLauncher) window.AndroidLauncher.initLocalModel(useGpu);
        }

        window.onModelInitSuccess = function() {
            isLocalModelReady = true;
            document.getElementById('local-model-logs').innerText = "> Engine Initialized successfully.";
            document.getElementById('local-model-badge').innerText = 'ACTIVE (LOCAL)';
            document.getElementById('local-model-badge').className = 'bg-green-900/80 text-[9px] px-2 py-1 rounded orbitron text-green-300 animate-pulse';
            
            const btn = document.getElementById('btn-init-model');
            btn.innerText = "ENGINE ONLINE";
            btn.classList.remove('animate-pulse');
            
            setSystemPrompt(true);
        };

        window.onModelInitError = function(err) {
            document.getElementById('local-model-logs').innerText = "> Init Error: " + err;
            document.getElementById('local-model-logs').className = "fira-code text-[10px] text-red-500 mt-2 mb-3 whitespace-pre-line";
            const btn = document.getElementById('btn-init-model');
            btn.innerText = "INIT FAILED";
            btn.classList.remove('animate-pulse', 'opacity-50', 'pointer-events-none');
        };

        window.onAiMessageChunk = function(text) {
            // Clean out emojis and markdown symbols to keep the UI and TTS clean
            const cleanedToken = cleanAiText(text);
            if (!cleanedToken) return;

            isAiGenerating = true;
            localFullResponseText += cleanedToken;
            const txt = document.getElementById('transcript');
            txt.innerHTML += cleanedToken;
            const box = document.getElementById('transcript-box');
            box.scrollTop = box.scrollHeight;
            
            handleStreamingVoice(cleanedToken);
        };

        window.onAiMessageDone = function() {
            if (sentenceBuffer.trim().length > 0) {
                speakResponse(sentenceBuffer.trim() + " ", true);
                sentenceBuffer = "";
            }
            isAiGenerating = false;
            isFirstSpeechChunk = true; // Reset the early-trigger flag for the next response
            if (queuedUtterances <= 0) finishAndStartMic();
        };

        window.onAiMessageError = function(err) {
            isAiGenerating = false;
            const txt = document.getElementById('transcript');
            txt.innerHTML += `\n<span class="text-red-500">[Local Engine Error: ${err}]</span>`;
            resetVoiceUI();
        };

        window.onload = async () => {
            initPersonaInputs();
            checkLocalModelStatus();
            getNexusApiUrl().then(url => { if (url) setSystemPrompt(true); });
            initHardwareWs();
            
            // Show installed version
            if (window.AndroidLauncher && window.AndroidLauncher.getInstalledVersion) {
                document.getElementById('installed-version-label').innerText = 'v' + window.AndroidLauncher.getInstalledVersion();
            }

            setInterval(() => {
                let cpu = 20 + Math.floor(Math.random() * 15);
                if (window.AndroidLauncher && typeof window.AndroidLauncher.getRealCpuLoad === 'function') {
                    cpu = window.AndroidLauncher.getRealCpuLoad();
                }
                document.getElementById('cpu-val').innerText = cpu + "%";
                document.getElementById('cpu-bar').style.width = cpu + "%";
            }, 1000);
            
            setInterval(fetchRealTelemetry, 2000);

            document.getElementById('voice-trigger').addEventListener('click', handleVoiceTriggerClick);
            document.getElementById('ai-send-btn').addEventListener('click', () => processAiCommandStream(document.getElementById('ai-text-input').value));
        };
    