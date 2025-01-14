// top-level processor abstraction, defining all our sea-wolf functionality
// it doesn't depend on norns

PirateRadio {

	//---------------------
	//----- class variables
	//----- these are global - best used sparsely, as constants etc

	// the `<` tells SC to create a getter method
	// (it's useful to at least have getters for everything during development)
	classvar <numStreams;
	classvar <defaultFileLocation = "/home/we/dust/audio/pirates";

	//------------------------
	//----- instance variables
	//----- created for each new `PirateRadio` object

	// where all our loot is stashed
	var <fileLocation;
	// all the loots
	var <filePaths;

	//--- busses
	// array of busses for streams
	var <streamBusses;
	// a bus for noise
	var <noiseBus;
	// final output bus. effects can be performed on this bus in-place
	var <outputBus;


	//--- child components
	var streamPlayers;
	var noise;
	var selector;
	var effects;

	//--- synths

	// final output synth
	var outputSynth;

	//--------------------
	//----- class methods

	// most classes have a `*new` method
	*new {
		arg server, fileLocation;
		// this is a common pattern:
		// construct the superclass, then call our init function on it
		// (beware that the superclass cannot also have a method named `init`)
		^super.new.init(server, fileLocation);
	}


	//----------------------
	//----- instance methods

	// initialize a new `PirateRadio` object / allocate resources
	init {
		arg server, fileLocation;

		if (fileLocation.isNil, { fileLocation = defaultFileLocation; });
		this.scanFiles;

		//--------------------
		//-- create busses; all of them are stereo
		streamBusses = Array.fill(numStreams, {
			Bus.audio(server, 2);
		});

		noiseBus = Bus.audio(server, 2);

		outputBus = Bus.audio(server, 2);

		//------------------
		//-- create synths and components

		// since we are routing audio through several Synths in series,
		// it's important to manage their order of execution.
		// here we do that in the simplest way:
		// each component places its synths at the end of the server's node list
		// so, the order of instantation is also the order of execution
		streamPlayers = Array.fill(numStreams, { arg i;
			PradStreamPlayer.new(server, streamBusses[i]);
		});

		noise = PradNoise.new(server, noiseBus);

		selector = PradStreamSelector.new(server, streamBusses, noiseBus, outputBus);

		effects = PradEffects.new(server, outputBus);

		outputSynth = {
			arg threshold=0.99, lookahead=0.2;
			var snd;
			snd = In.ar(outputBus, 2);
			snd = Limiter.ar(snd, threshold, lookahead).clip(-1, 1);
			Out.ar(0, snd);
		}.play(target:server, addAction:\addToTail);
	}

	// refresh the list of sound files
	scanFiles {
		filePaths = PathName.new(fileLocation).files;
		///... update the streamPlayers or whatever
	}

	// set the dial position
	setDial {
		arg value;
		noise.setDial(value);
		selector.setDial(value);
		// now... there is a little issue/question here.
		// the simplest way to manage the multiple streams is to just have them all running all the time.
		// but this won't be feasible if there are many streams
		// it may be better, but def. more complicated,
		// to have the selector hold references to the streamPlayers themselves,
		// and pause un-selected streams as appropriate.
	}

	// set an effect parameter
	setFxParam {
		arg key, value;
		effects.setParam(key, value);
	}

	// stop and free resources
	free {
		// by default i tend to free stuff in reverse order of alloctaion
		outputSynth.free;

		effects.free;
		selector.free;
		noise.free;
		streamPlayers.do({ arg player; player.free; });

		outputBus.free;
		noiseBus.free;
		streamBusses.do({ arg bus; bus.free; });
	}
}

//------------------------------------
//-- helper classes
//
// supercollier doesn't have namespaces unfortunately (probably in v4)
// so this is a common pattern: use a silly class-name prefix as a pseudo-namespace

PradStreamPlayer {
	// streaming buffer(s) and synth(s)..
	// (probably want 2x of each, to cue/crossfade)
	var <bufs;
	var <synths;

	*new {
		arg server, outBus;
		^super.new.init(server, outBus);
	}

	init {
		//////////////////
		// this one could get a little involved..
		///////////////////
	}

	/////////////////
	// will need various methods to manage playlist, cue sound files
	// playlist / cued file management might be better done in the owning `PirateRadio`...
	// .. in which case this guy will also need a reference to its owner,
	// to inform when current soundfile is runnning out, etc

	fileFinished {
	}

	pickFromLootPile {
	}

	outOfLoot {
	}
	////////////////

	free {
		synths.do({ arg synth; synth.free; });
		bufs.do({ arg buf; buf.free; });
	}
}


// noise generator
PradNoise {
	var <synth;

	*new {
		arg server, outBus;
		^super.new.init(server, outBus);
	}

	init {
		arg server, outBus;
		synth = {
			// probably want to vary the noise somehow depending on dial position, yar?
			arg dial;

			var snd;

			///////////////
			////
			snd = BrownNoise.ar(0.2).dup + LPf.ar(Dust.ar(1), LFNoise2.kr(0.1).linexp(100, 4000).dup);
			snd = snd + SinOsc.ar(LFNoise2.kr(LFNoise1.kr(0.1).linlin(1, 100).linexp(60, 666)));
			snd = snd.ring1(SinOsc.ar(LFNoise2.kr(LFNoise1.kr(0.1).linlin(1, 100).linexp(60, 666))).dup);
			snd = snd + HenonC.ar(a:LFNoise2.kr(0.2).linlin(-1,1,1.1,1.5).dup);
			//... or whatever
			////////////////

			// force everything down to stereo
			snd = Mix.new(snd.clump(2));

			Out.ar(outBus, snd);
		}.play(target:server, addAction:\addToTail);
	}

	setDial {
		arg value;
		synth.set(\dial, value);
	}

	free {
		synth.free;
	}
}


// effects processor
// applies effects to stereo bus
PradEffects {
	var synth;
	*new {
		arg server, bus;
		^super.new.init(server, bus);
	}

	init { arg server, bus;

		// also could define the SynthDef explicitly
		synth = {
			// ... whatever args
			arg chorusRate=0.2, preGain=1.0;

			var signal;
			signal = In.ar(bus, 2);

			////////////////
			signal = DelayC.ar(signal, delayTime:LFNoise2.kr(chorusRate).linlin(-1,1, 0.01, 0.06));
			signal = Greyhole.ar(signal);
			signal = (signal*preGain).distort.distort;
			//... or whatever
			///////////

			// `ReplaceOut` overwrites the bus contents (unlike `Out` which mixes)
			// so this is how to do an "insert" processor
			ReplaceOut.ar(bus, signal);

		}.play(target:server addAction:\addToTail);
	}



	free {
		synth.free;
	}
}


// this will be responsible for selecting / mixing all the streams / noise
PradStreamSelector {
	var <synth;

	*new {
		arg server, streamBusses, noiseBus, outBus;
		^super.new.init(server);
	}

	init { arg server, streamBusses, noiseBus, outBus;
		var numStreams = streamBusses.size;

		// also could define the SynthDef explicitly
		synth = {
			arg dial; // the selection parameter
			var streams, noise, mix;
			streams = streamBusses.collect({ arg bus;
				In.ar(bus.index, 2)
			});
			noise =In.ar(noiseBus, 2);


			///////////////////////////
			// mix =  ....
			//////////////////////////

			Out.ar(outBus.index, mix);
		}.play(target:server, addAction:\addAfter);
	}

	setDial {
		arg value;
		synth.set(\dial, value);
	}

	free {
		synth.free;
	}
}









////////////////////
////////////////////
/// little bonus...

PradStereoBitSaturator {
	classvar <compressCurve;
	classvar <expandCurve;

	var <compressBuf, <expandBuf, <synth;

	*initClass {
		var n, mu, unit;
		n = 512;
		mu = 255;
		unit = Array.fill(n, {|i| i.linlin(0, n-1, -1, 1) });
		compressCurve = unit.collect({ |x|
			x.sign * log(1 + mu * x.abs) / log(1 + mu);
		});
		expandCurve = unit.collect({ |y|
			y.sign / mu * ((1+mu)**(y.abs) - 1);
		});
	}

	*new {
		arg server, target, bus;
		^super.new.init(server, target, bus);
	}

	init {
		arg server, target, bus;

		compressBuf = Buffer.loadCollection(server, Signal.newFrom(compressCurve).asWavetableNoWrap);
		expandBuf = Buffer.loadCollection(server, Signal.newFrom(expandCurve).asWavetableNoWrap);

		synth = {
			arg steps = 256, compAmt=1, expAmt=1;
			var src, comp, x, crush, exp;
			src = In.ar(bus.index, 2);
			comp = Shaper.ar(compressBuf.bufnum, src);
			x = SelectX.ar(compAmt, [src, comp]);
			crush = (x.abs * steps).round * x.sign / steps;
			exp = Shaper.ar(expandBuf.bufnum, crush);
			ReplaceOut.ar(bus.index, SelectX.ar(expAmt, [crush, exp]));
		}.play(target:target, addAction:\addAfter);
	}

	setParam {
		arg key, value;
		synth.set(key, value);
	}

	free {
		synth.free;
		expandBuf.free;
		compressBuf.free;
	}
}