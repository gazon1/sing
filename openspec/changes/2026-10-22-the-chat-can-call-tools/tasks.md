## 1. The loop

- [x] The streaming path offers the model the tools that survived the kill
      switch, with their descriptions
- [x] A round of the conversation collects the text and the tool requests the
      model made
- [x] A tool request is run, and its answer is put back into the conversation
      in the shape the model reads
- [x] The assistant's own turn — its text and its requests — is kept as one
      turn, so a turn that said "let me look" and then asked is remembered as
      both
- [x] A request that carries no identifier is given one, and the same
      identifier is used on the request and on its answer
- [x] The text of a turn reaches the reader as it is produced
- [x] The loop is bounded, and the turn after the bound offers no tools
- [x] A tool that fails, and a tool that does not exist, are answers the model
      can read

## 2. The tools that are offered

- [x] The registry the non-streaming path uses is the same one the streaming
      path is offered
- [x] A tool that records its use records the use of the streaming path too
- [x] A recording wrapper does not change what a tool's answer looks like

## 3. The prompt

- [x] The chat's system prompt says the tools are there, and says that the
      assistant must not invent the user's data
- [x] The system prompt asks for a lookup before an answer about the user's
      own data

## 4. The tests

- [x] The loop: a question answered without a tool is answered in one turn
      (`ToolCallLoopTest`)
- [x] The loop: the model may call a tool, and the tool really runs
      (`ToolCallLoopTest`)
- [x] The loop: the tool's answer reaches the model
      (`ToolCallLoopTest`)
- [x] The loop: a call that no tool answers is an error the model can read
      (`ToolCallLoopTest`)
- [x] The loop: a tool that fails does not end the conversation
      (`ToolCallLoopTest`)
- [x] The loop: a model that keeps calling tools is stopped, and the last
      call is still honoured (`ToolCallLoopTest`)
- [x] The loop: the tools the model is offered are the ones the loop was given
      (`ToolCallLoopTest`)
