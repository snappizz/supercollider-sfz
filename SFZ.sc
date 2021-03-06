// Copyright (c) 2015 Nathan Ho.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

SFZ {
	classvar <controlOpcodes, <regionOpcodes;

	var <server;
	var <target;
	var <lineNo, curHeader, context, curRegion;
	var <sfzPath;
	var badOpcodes;
	var <globalOpcodes, <groupOpcodes;
	var <controlOpcodes;
	var <regions;
	var <buffers;
	var <nodes;
	var <>outbus, <>amp;

	*initClass {
		controlOpcodes = (
			default_path: [\string, nil],
			octave_offset: [\int, 0],
			note_offset: [\int, 0]
		);

		regionOpcodes = (
			lochan: [\int, 1],
			hichan: [\int, 16],
			lokey: [\note, 0],
			hikey: [\note, 127],
			lovel: [\int, 0],
			hivel: [\int, 127],
			trigger: [\symbol, \attack, [\attack, \release, \first, \legato]],
			seq_length: [\int, 1],
			seq_position: [\int, 1],

			loop_mode: [\symbol, \no_loop, [\no_loop, \one_shot]],

			transpose: [\int, 0],
			tune: [\int, 0],
			pitch_keycenter: [\note, 60],
			pitch_veltrack: [\int, 0],

			volume: [\float, 0.0],

			ampeg_delay: [\float, 0.0],
			ampeg_start: [\float, 0.0],
			ampeg_attack: [\float, 0.0],
			ampeg_hold: [\float, 0.0],
			ampeg_decay: [\float, 0.0],
			ampeg_sustain: [\float, 100.0],
			ampeg_release: [\float, 0.0],

			ampeg_vel2delay: [\float, 0.0],
			ampeg_vel2attack: [\float, 0.0],
			ampeg_vel2hold: [\float, 0.0],
			ampeg_vel2decay: [\float, 0.0],
			ampeg_vel2release: [\float, 0.0],

			pitcheg_delay: [\float, 0.0],
			pitcheg_start: [\float, 0.0],
			pitcheg_attack: [\float, 0.0],
			pitcheg_hold: [\float, 0.0],
			pitcheg_decay: [\float, 0.0],
			pitcheg_sustain: [\float, 100.0],
			pitcheg_release: [\float, 0.0],
			pitcheg_depth: [\int, 0],

			pitcheg_vel2delay: [\float, 0.0],
			pitcheg_vel2attack: [\float, 0.0],
			pitcheg_vel2hold: [\float, 0.0],
			pitcheg_vel2decay: [\float, 0.0],
			pitcheg_vel2release: [\float, 0.0],

			pitchlfo_delay: [\float, 0.0],
			pitchlfo_fade: [\float, 0.0],
			pitchlfo_freq: [\float, 0.0],
			pitchlfo_depth: [\int, 0],

			fil_type: [\symbol, \lpf_2p, [\lpf_1p, \hpf_1p, \lpf_2p, \hpf_2p, \bpf_2p, \brf_2p]],
			cutoff: [\float, nil],
			resonance: [\float, 0],
			fil_veltrack: [\int, 0],

			sample: { |opcodesDict, value, lineNo|
				// Many soundfonts use backslashed directories
				opcodesDict.sample = value.replace("\\", "/");
			},
			key: { |opcodesDict, value, lineNo|
				value = SFZ.validate([\note, nil], value, lineNo);
				opcodesDict.lokey = value;
				opcodesDict.hikey = value;
				opcodesDict.pitch_keycenter = value;
			}
		);
	}

	*new { arg path, server, badOpcodes = \error;
		^super.new.init(path, server, badOpcodes);
	}

	init { |path, argServer, argBadOpcodes|
		sfzPath = path;
		server = argServer ? Server.default;
		target = server;
		server.isKindOf(Group).if {
			server = server.server; // lol
		};
		outbus = 0;
		amp = 0.5;
		badOpcodes = argBadOpcodes;

		nodes = ();

		regions = [];

		globalOpcodes = ();
		groupOpcodes = ();
		controlOpcodes = ();

		SFZ.controlOpcodes.keysValuesDo { |opcode, spec|
			spec.isArray.if {
				controlOpcodes[opcode] = spec[1];
			};
		};

		sfzPath.notNil.if {
			controlOpcodes.default_path = PathName(sfzPath).pathOnly;
			this.parse;
		};
	}

	// specs take on the form [type, default]
	// type can be \string, \int, \float, or \note.

	*validate { |spec, value, lineNo=0|

		value = switch (spec[0])
			{ \string } { value }
			{ \int } {
				if ("^[-+]?\\d+$".matchRegexp(value).not) {
					^Error("Bad opcode value '%' on line %. Expected an integer.".format(value, lineNo)).throw;
				};
				value.asInteger;
			}
			{ \float } {
				if ("^[-+]?(\\d+(\\.\\d*)?|\\.\\d+)$".matchRegexp(value.toLower).not) {
					^Error("Bad opcode value '%' on line %. Expected a float.".format(value, lineNo)).throw;
				};
				value.asFloat;
			}
			{ \note } {
				var match;
				match = value.toLower.findRegexp("^([a-g])([b#]?)([-+]?\\d+)$");
				if (match.isEmpty) {
					if ("^[-+]?\\d+$".matchRegexp(value)) {
						value.asInteger;
					} {
						^Error("Bad opcode value '%' on line %. Expected a MIDI note.".format(value, lineNo)).throw;
					};
				} {
					var noteName, alteration, octave;
					noteName = [0, 2, 4, 5, 7, 9, 11]["cdefgab".indexOf(match[1][1][0])];
					alteration = match[2][1][0];
					alteration = if(alteration.notNil, { if(alteration == $b, -1, 1) }, 0);
					octave = match[3][1].asInteger;
					octave + 1 * 12 + noteName + alteration;
				};
			}
			{ \symbol } {
				if (spec[2].includes(value.asSymbol).not) {
					^Error("Bad opcode value '%' on line %. Expected one of %.".format(value, lineNo, spec[2])).throw;
				};
				value.asSymbol;
			};

		^value;
	}

	parse {
		File.exists(sfzPath).not.if {
			^Error("File '%' does not exist".format(sfzPath)).throw;
		};
		File.use(sfzPath, "r", { |f|
			this.parseString(f.readAllString);
		});
	}

	parseString { |sfzString|

		var opcodeWarning = true;

		// Split into lines
		sfzString.replace("\r\n", "\n").split($\n).do { |line, i|

			lineNo = i + 1;

			// Remove comments
			line.find("//").notNil.if {
				line = line[..line.find("//") - 1];
			};

			line = line.stripWhiteSpace;

			// Simple lexer
			while { line.notEmpty } {
				var match, advance;
				advance = 0;
				case
				// whitespace
				{ match = line.findRegexp("^\\s+"); match.notEmpty } {
					// do nothing
					advance = match[0][1].size;
				}
				// headers
				{ match = line.findRegexp("^<(\\w+)>"); match.notEmpty } {
					this.parseHeader(match[1][1].asSymbol);
					advance = match[0][1].size;
				}
				// opcodes
				// We have to cope with the SFZ format's TERRIBLE decision to allow spaces in opcodes
				// and allow multiple opcodes on the same line.

				// (\w+) - opcode key
				// = - opcode =
				// (.*?) - opcode value
				// \s+ - whitespace between this and the next opcode
				// \w+ - next opcode key
				// = - next opcode =
				{ match = line.findRegexp("^((\\w+)=(.*?))\\s+\\w+="); match.notEmpty } {
					opcodeWarning.if {
						"To avoid ambiguity, you should not place multiple SFZ opcodes in a line.".warn;
						opcodeWarning = false;
					};
					this.parseOpcode(match[2][1].asSymbol, match[3][1]);
					advance = match[1][1].size;
				}
				// Otherwise, if there's a valid opcode, let it extend to the rest of the line
				{ match = line.findRegexp("^(\\w+)=(.*)"); match.notEmpty } {
					this.parseOpcode(match[1][1].asSymbol, match[2][1]);
					advance = match[0][1].size;
				}

				{ ^Error("Syntax error on line %".format(lineNo)).throw; };

				// Remove the matched characters at the beginning of the string
				line = line[advance..];
			};

		};

	}

	parseHeader { |header|

		if ([\control, \global, \group, \region].includes(header)) {

			switch (header)
			{ \control } {
				groupOpcodes = ();
				context = controlOpcodes;
			}

			{ \global } {
				groupOpcodes = ();
				context = globalOpcodes;
			}

			{ \group } {
				groupOpcodes = ();
				context = groupOpcodes;
			}

			{ \region } {
				var regionOpcodes = ();
				regionOpcodes.putAll(globalOpcodes, groupOpcodes);
				curRegion = SFZRegion(this, regionOpcodes);
				context = curRegion.opcodes;
				regions = regions.add(curRegion);
			};

			curHeader = header;

		} {
			^Error("Unrecognized header <%> on line %.".format(header, lineNo)).throw;
		};

	}

	parseOpcode { |opcode, value|
		(curHeader == \control).if {
			var spec = SFZ.controlOpcodes[opcode];
			spec.notNil.if {
				spec.isArray.if {
					value = SFZ.validate(spec, value, lineNo);
					context[opcode] = value;
				} {
					spec.value(context, value);
				};
			} {
				var errorStr = "Unrecognized control opcode '%' on line %.".format(opcode, lineNo);
				(badOpcodes == \error).if {
					^Error(errorStr).throw;
				} {
					^errorStr.warn;
				};
			};
		} {
			var spec = SFZ.regionOpcodes[opcode];
			spec.notNil.if {
				spec.isArray.if {
					value = SFZ.validate(spec, value, lineNo);
					context[opcode] = value;
				} {
					spec.value(context, value);
				};
			} {
				var errorStr = "Unrecognized region opcode '%' on line %.".format(opcode, lineNo);
				(badOpcodes == \error).if {
					^Error(errorStr).throw;
				} {
					^errorStr.warn;
				};
			};
		};
	}

	regionsDo { |cb|
		regions.do { |region|
			cb.value(region);
		};
	}

	prepare { |action|
		var regionsByPath;
		var paths, loadBuffer;

		server.serverRunning.not.if {
			^Error("Server not booted").throw;
		};

		regionsByPath = Dictionary();
		buffers = Dictionary();

		// Group together regions by path so no duplicate buffers are loaded.
		this.regionsDo { |region|
			var path = controlOpcodes.default_path +/+ region.opcodes.sample;
			region.path = path;
			regionsByPath[path].isNil.if { regionsByPath[path] = []; };
			regionsByPath[path] = regionsByPath[path].add(region);
		};

		// Get paths

		paths = regionsByPath.keys.asArray;

		// Asynchronously load all buffers

		loadBuffer = { |action|
			// Get the next path
			var path = paths.pop;
			path.isNil.if {
				// If paths.pop returns nil, we're done
				action.value;
			} {
				// Get all regions associated with this path
				var regions = regionsByPath[path];
				// Load the buffer
				buffers[path] = Buffer.read(server, path, action: { |buf|
					"Loaded buffer % of %".format(regionsByPath.size - paths.size, regionsByPath.size).postln;
					// After this buffer is done, call loadBuffer again
					loadBuffer.value(action);
				});
				// Assign the buffer to all the regions
				regions.do { |region|
					region.buffer = buffers[path];
				};
			};
		};

		loadBuffer.value {
			// Send all synthdefs
			this.regionsDo { |region|
				region.makeSynthDef;
			};
			action.notNil.if {
				action.value;
			}
		};
	}

	noteOn { |vel = 64, num = 60, chan = 1|
		var node = SFZNode(this);
		server.makeBundle(nil, {
			this.regionsDo { |region|
				var synth = region.noteOn(vel, num, chan);
				synth.notNil.if {
					node.add(synth);
				};
			};
		});
		nodes[chan].isNil.if {
			nodes[chan] = ();
		};
		nodes[chan][num].isNil.if {
			nodes[chan][num] = Set();
		};
		nodes[chan][num] = nodes[chan][num].add(node);
		^node;
	}

	noteOff { |vel = 64, num = 60, chan = 1|
		nodes[chan].notNil.if {
			nodes[chan][num].notNil.if {
				nodes[chan][num].do { |node|
					node.release;
				};
				nodes[chan][num] = nil;
			};
		};
	}

	panic {
		nodes.do { |chan|
			chan.do { |nodes|
				nodes.do { |node|
					node.release;
				};
			};
		};
	}

	free {
		buffers.notNil.if {
			buffers.values.do { |buf|
				buf.free;
			};
		};
	}
}

SFZRegion {

	classvar <opcodeSpecs, <specialOpcodes;

	var outbus;
	var <parent;
	var <opcodes;
	var <>path;
	var <>buffer;
	var <defName;
	var seqCounter;

	*initClass {
	}

	*new { |parent, opcodes = nil|
		^super.new.init(parent, opcodes);
	}

	init { |argParent, argOpcodes|

		parent = argParent;
		outbus = parent.outbus;

		opcodes = if (argOpcodes.isNil) { () } { argOpcodes.copy };

		SFZ.regionOpcodes.keysValuesDo { |opcode, spec|
			opcodes[opcode].isNil.if {
				spec.isArray.if {
					opcodes[opcode] = spec[1];
				};
			};
		};

		seqCounter = 1;
	}

	*dahdsr { |delay, start, attack, hold, decay, sustain, release|
		^Env(
			[0, 0, start / 100, 1, 1, sustain / 100, 0],
			[delay, 0, attack, hold, decay, release],
			-4.0,
			5
		);
	}

	*lfo { |delay, fade, freq|
		^SinOsc.kr(freq) * EnvGen.kr(Env([0, 0, 1], [delay, fade], 2));
	}

	ar { |freq, vel, gate|
		var o, note, snd, autoEnv, autoLfo, bufRate;
		var normVel;

		o = opcodes;
		normVel = vel / 127;

		autoEnv = { |prefix|
			var names = [\delay, \start, \attack, \hold, \decay, \sustain, \release];
			SFZRegion.dahdsr(*names.collect { |name|
				((name == \start) or: { name == \sustain }).if {
					o[(prefix ++ \_ ++ name).asSymbol]
				} {
					o[(prefix ++ \_ ++ name).asSymbol] +
						(normVel * o[(prefix ++ \_vel2 ++ name).asSymbol])
				};
			});
		};

		autoLfo = { |prefix|
			var names = [\delay, \fade, \freq];
			SFZRegion.lfo(*names.collect { |name| o[(prefix ++ \_ ++ name).asSymbol] });
		};

		note = freq.cpsmidi;

		if (o.pitch_veltrack != 0) {
			note = note + ((normVel - 0.5) * (o.pitch_veltrack / 100));
		};
		if (o.pitcheg_depth != 0) {
			note = note +
				((o.pitcheg_depth / 100) * EnvGen.kr(autoEnv.(\pitcheg), gate));
		};
		if (o.pitchlfo_depth != 0) {
			note = note +
				((o.pitchlfo_depth / 100) * autoLfo.(\pitchlfo));
		};

		freq = note.midicps;

		bufRate = BufRateScale.kr(buffer) * freq / o.pitch_keycenter.midicps;
		snd = PlayBuf.ar(buffer.numChannels, buffer, bufRate, doneAction: if(o.loop_mode == \one_shot, 2, 0));

		if (o.cutoff.notNil) {
			var cutoff = o.cutoff;

			var cutoffNote = cutoff.cpsmidi;

			if (o.fil_veltrack != 0) {
				cutoffNote = cutoffNote + ((normVel - 0.5) * (o.fil_veltrack / 100));
			};

			cutoff = Clip.kr(cutoffNote.midicps, 0, parent.server.sampleRate * 0.4);

			switch (o.fil_type)
			{ \lpf_1p } { snd = OnePole.ar(snd, 1 - (cutoff / parent.server.sampleRate)); }
			{ \hpf_1p } { snd = OnePole.ar(snd, (cutoff / parent.server.sampleRate).neg); }
			{ \lpf_2p } { snd = RLPF.ar(snd, cutoff, o.resonance.dbamp.reciprocal); }
			{ \hpf_2p } { snd = RHPF.ar(snd, cutoff, o.resonance.dbamp.reciprocal); }
			{ \bpf_2p } { snd = BPF.ar(snd, cutoff, o.resonance.dbamp.reciprocal); }
			{ \brf_2p } { snd = BRF.ar(snd, cutoff, o.resonance.dbamp.reciprocal); };
		};

		snd = snd * EnvGen.ar(autoEnv.(\ampeg), if(o.loop_mode == \one_shot, 1, gate), doneAction: if(o.loop_mode == \one_shot, 0, 2));
		snd = snd * (-20 * ((127**2) / (vel**2)).log).dbamp;
		snd = snd * o.volume.dbamp;
		^snd;
	}

	makeSynthDef {
		var o = opcodes;

		defName = ("sfzSample-" ++ ({ "0123456789abcdefghijklmnopqrstuvwxyz".choose }!32).join("")).asSymbol;

		SynthDef(defName, {
			|out = 0, amp = 1.0, gate = 1, freq = 440, vel = 64|
			Out.ar(out, this.ar(freq, vel, gate) * amp);
			// Out.ar(out, Pan2.ar(this.ar(freq, gate), 0, amp));
		}).send(parent.server);
	}

	play { |vel, num|
		var o = opcodes;
		^Synth(defName, [
			\out, parent.outbus,
			\amp, parent.amp,
			\vel, vel,
			\freq, (num + o.transpose + (o.tune / 100)).midicps
		], target: parent.server);
	}

	noteOn { |vel, num, chan|
		var o = opcodes;

		var node = if ((o.lochan <= chan) and: { chan <= o.hichan }
			and: { o.lokey <= num } and: { num <= o.hikey }
			and: { o.lovel <= vel } and: { vel <= o.hivel }
			and: { seqCounter == o.seq_position }) {
			this.play(vel, num);
		} { nil };

		seqCounter = seqCounter + 1;
		(seqCounter > o.seq_length).if {
			seqCounter = 1;
		};

		^node;
	}

}

SFZNode {

	var <synths;
	var <parent;

	*new { |parent|
		^super.new.init(parent);
	}

	init { |argParent|
		parent = argParent;
	}

	add { |synth|
		synths = synths.add(synth);
	}

	release {
		parent.server.makeBundle(nil, {
			synths.do { |synth|
				synth.set(\gate, 0);
			};
		});
	}

	free {
		parent.server.makeBundle(nil, {
			synths.do { |synth|
				synth.free;
			};
		});
	}

}