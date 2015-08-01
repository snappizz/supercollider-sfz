SFZ {
	var <server;
	var <lineNo, curHeader, context, curRegion, curGroup;
	var <sfzPath, <sfzDir;
	var <groups;
	var opcodeSpecs, specialOpcodes;
	var <opcodes;
	var <buffers;
	var <nodes;

	*new { arg path, server;
		^super.new.init(path, server);
	}

	init { |path, argServer|
		sfzPath = path;
		server = argServer ? Server.default;

		groups = [];

		opcodes = ();

		opcodeSpecs = (
			default_path: [\string, nil],
			octave_offset: [\int, 0],
			note_offset: [\int, 0]
		);

		specialOpcodes = ();

		opcodeSpecs.keysValuesDo { |opcode, spec|
			opcodes[opcode] = spec[1];
		};

		if (path.notNil) {
			sfzDir = PathName(sfzPath).pathOnly;
			opcodes.default_path = PathName(sfzPath).pathOnly;
		};
	}

	// specs take on the form [type, default, lo, hi]
	// type can be \string, \int, \float, or \note.
	// default is ignored in this method, but it is used in initialization.
	// lo and hi are optional ranges for the parameter.

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
				if ("^[-+]?\\d+(\\.\\d*)?$".matchRegexp(value.toLower).not) {
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
			};

		if (spec[2].notNil) {
			if (value < spec[2] or: { value > spec[3] }) {
				^Error("Opcode value '%' out of range on line %. Expected range: % to %".format(value, lineNo, spec[2], spec[3])).throw;
			};
		};

		^value;
	}

	addGroup {
		var group = SFZGroup(this);
		groups = groups.add(group);
		^group;
	}

	parse {
		File.use(sfzPath, "r", { |f|
			this.parseString(f.readAllString);
		});
	}

	parseString { |sfzString|

		// Split into lines
		sfzString.replace("\r\n", "\n").split($\n).do { |line, i|

			lineNo = i + 1;

			// Remove comments
			if (line.find("//").notNil) {
				line = line[..line.find("//") - 1];
			};

			// Simple lexer
			while { line.notEmpty } {
				var match;
				case
				// whitespace
				{ match = line.findRegexp("^\\s+"); match.notEmpty } {
					// do nothing
				}
				// headers
				{ match = line.findRegexp("^<(\\w+)>"); match.notEmpty } {
					this.parseHeader(match[1][1].asSymbol);
				}
				// opcodes extend to the end of the line
				// The original SFZ format made a terrible decision to allow both spaces in opcodes
				// and multiple opcodes on the same line.
				{ match = line.findRegexp("^(\\w+)=(.*)"); match.notEmpty } {
					this.parseOpcode(match[1][1], match[2][1]);
				}
				{ ^Error("Syntax error on line %".format(lineNo)).throw; };

				// Remove the matched characters at the beginning of the string
				line = line[match[0][1].size..];
			};

		};

	}

	parseHeader { |header|

		if ([\control, \group, \region].includes(header)) {

			switch (header)
			{ \control } {
				context = this;
				curGroup = nil;
				curRegion = nil;
			}

			{ \group } {
				context = this.addGroup;
				curGroup = context;
				curRegion = nil;
			}

			{ \region } {
				if (curGroup.isNil) {
					curGroup = this.addGroup;
				};
				context = curGroup.addRegion;
				curRegion = context;
			};

			curHeader = header;

		} {
			^Error("Unrecognized header <%> on line %.".format(header, lineNo)).throw;
		};

	}

	parseOpcode { |opcode, value|
		if (context.notNil) {
			context.setOpcode(opcode.asSymbol, value);
		}
	}

	setOpcode { |opcode, value|
		if (opcodeSpecs[opcode].notNil) {
			value = SFZ.validate(opcodeSpecs[opcode], value, lineNo);
			opcodes[opcode] = value;
		} {
			if (specialOpcodes[opcode].notNil) {
				specialOpcodes[opcode].value(value);
			} {
				^Error("Unrecognized control opcode '%' on line %.".format(opcode, lineNo)).throw;
			};
		};
	}

	regionsDo { |cb|
		groups.do { |group|
			group.regions.do { |region|
				cb.value(region);
			};
		};	
	}

	prepare { |action|
		var makeBuf = { |path, cb| var b = Buffer.read(server, path, action: cb); b; };
		var regionsByPath = Dictionary();
		var bufCount, bufsDone;
		buffers = Dictionary();

		this.regionsDo { |region|
			var path = opcodes.default_path +/+ region.opcodes.sample;
			region.path = path;
			if (regionsByPath[path].isNil) { regionsByPath[path] = []; };
			regionsByPath[path] = regionsByPath[path].add(region);
		};

		bufCount = regionsByPath.keys.size;
		bufsDone = 0;
		regionsByPath.keysValuesDo { |path, regions|
			buffers[path] = makeBuf.value(path, { |buf|
				bufsDone = bufsDone + 1;
				if (bufsDone >= bufCount) {

					this.regionsDo { |region|
						region.makeSynthDef;
					};

					if (action.notNil) {
						action.value;
					};
				};
			});
			regions.do { |region|
				region.buffer = buffers[path];
			};
		};
	}

	play { |vel, num, chan|
		var node = SFZNode(this);
		server.makeBundle(nil, {
			this.regionsDo { |region|
				var o = region.opcodes;
				if ((o.lochan <= chan) and: { chan <= o.hichan }
					and: { o.lokey <= num } and: { num <= o.hikey }
					and: { o.lovel <= vel } and: { vel <= o.hivel }) {
					node.add(Synth(region.defName, [
						\freq, (num + o.transpose + (o.tune / 100)).midicps
					]));
				};
			};
		});
		^node;
	}

	free {
		buffers.values.do { |buf|
			buf.free;
		};
	}
}

SFZRegion {

	var <parent;
	var <opcodes;
	var opcodeSpecs, specialOpcodes;
	var <>path;
	var <>buffer;
	var <defName;

	*new { |parent, opcodes = nil|
		^super.new.init(parent, opcodes);
	}

	init { |argParent, argOpcodes|

		parent = argParent;

		opcodes = if (argOpcodes.isNil) { () } { argOpcodes.copy };

		opcodeSpecs = (
			lochan: [\int, 1, 1, 16],
			hichan: [\int, 16, 1, 16],
			lokey: [\note, 0, 0, 127],
			hikey: [\note, 127, 0, 127],
			lovel: [\int, 0, 0, 127],
			hivel: [\int, 127, 0, 127],

			transpose: [\int, 0, -127, 127],
			tune: [\int, 0, -100, 100],
			pitch_keycenter: [\note, 60, 0, 127],

			volume: [\float, 0.0, -144, 6],

			ampeg_delay: [\float, 0.0, 0.0, 100.0],
			ampeg_start: [\float, 0.0, 0.0, 100.0],
			ampeg_attack: [\float, 0.0, 0.0, 100.0],
			ampeg_hold: [\float, 0.0, 0.0, 100.0],
			ampeg_decay: [\float, 0.0, 0.0, 100.0],
			ampeg_sustain: [\float, 100.0, 0.0, 100.0],
			ampeg_release: [\float, 0.0, 0.0, 100.0]
		);

		specialOpcodes = (
			sample: { |value|
				// Many older soundfonts use backslashed directories
				opcodes.sample = value.replace("\\", "/");
			},
			key: { |value|
				value = SFZ.validate([\note, nil, 0, 127], value, parent.lineNo);
				opcodes.lokey = value;
				opcodes.hikey = value;
				opcodes.pitch_keycenter = value;
			}
		);

		opcodeSpecs.keysValuesDo { |opcode, spec|
			if (opcodes[opcode].isNil) {
				opcodes[opcode] = spec[1];
			};
		};
	}

	setOpcode { |opcode, value|
		if (opcodeSpecs[opcode].notNil) {
			value = SFZ.validate(opcodeSpecs[opcode], value, parent.lineNo);
			opcodes[opcode] = value;
		} {
			if (specialOpcodes[opcode].notNil) {
				specialOpcodes[opcode].value(value);
			} {
				^Error("Unrecognized region opcode '%' on line %.".format(opcode, parent.lineNo)).throw;
			};
		};
	}

	makeSynthDef {
		var o = opcodes;

		defName = ("sfzSample-" ++ ({ "0123456789abcdefghijklmnopqrstuvwxyz".choose }!32).join("")).asSymbol;

		SynthDef(defName, {
			|out = 0, amp = 0.5, gate = 1, freq = 440|
			var snd, env;
			snd = PlayBuf.ar(1, buffer, BufRateScale.kr(buffer) * freq / o.pitch_keycenter.midicps);
			env = Env(
				[0, 0, o.ampeg_start / 100, 1, 1, o.ampeg_sustain / 100, 0],
				[o.ampeg_delay, 0, o.ampeg_attack, o.ampeg_hold, o.ampeg_decay, o.ampeg_release],
				-4.0,
				5
			);
			snd = snd * EnvGen.ar(env, gate, doneAction: 2);
			snd = snd * o.volume.dbamp;
			Out.ar(out, snd * amp);
		}).send(parent.server);

	}

}

SFZGroup : SFZRegion {

	var <regions;

	*new { |parent|
		^super.new.init(parent).groupInit;
	}

	groupInit {
		regions = [];
	}

	addRegion {
		var region = SFZRegion(parent, opcodes);
		regions = regions.add(region);
		^region;
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